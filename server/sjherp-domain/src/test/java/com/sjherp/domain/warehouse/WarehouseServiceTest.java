package com.sjherp.domain.warehouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DefaultDocumentNumberGenerator;
import com.sjherp.domain.common.numbering.InMemorySequenceProvider;
import com.sjherp.domain.inventory.StockChecker;

/**
 * 仓库档案领域服务测试：自动编号、编码唯一、必填校验、库位开关、启停规则。
 */
class WarehouseServiceTest {

    private static final String OPERATOR = "tester";

    /** 固定时钟：2026-06，自动编号应为 WH-202606-XXXX */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-12T08:00:00Z"), ZoneOffset.UTC);

    private InMemoryWarehouseRepository warehouseRepository;
    private WarehouseService service;

    @BeforeEach
    void setUp() {
        warehouseRepository = new InMemoryWarehouseRepository();
        service = new WarehouseService(warehouseRepository,
                new DefaultDocumentNumberGenerator(new InMemorySequenceProvider(), FIXED_CLOCK));
    }

    private WarehouseCommand command(String code, String name) {
        return new WarehouseCommand(code, name, "上海市嘉定区工业园 1 号", "老张", false);
    }

    @Test
    void 编码为空时自动编号_WH前缀年月序号() {
        Warehouse first = service.create(command(null, "原料仓"), OPERATOR);
        Warehouse second = service.create(command("", "成品仓"), OPERATOR);
        assertEquals("WH-202606-0001", first.getCode());
        assertEquals("WH-202606-0002", second.getCode());
        assertNotNull(first.getId());
        assertEquals(ArchiveStatus.ENABLED, first.getStatus());
    }

    @Test
    void 手填编码可用_重复被拒绝() {
        service.create(command("WH-RAW", "原料仓"), OPERATOR);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(command("WH-RAW", "山寨原料仓"), OPERATOR));
        assertTrue(e.getMessage().contains("已存在"));
    }

    @Test
    void 更新可改编码_与他人重复被拒绝_与自己相同放行() {
        Warehouse first = service.create(command("WH-RAW", "原料仓"), OPERATOR);
        service.create(command("WH-FIN", "成品仓"), OPERATOR);

        // 改成他人编码 → 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> service.update(first.getId(), command("WH-FIN", "原料仓"), OPERATOR));
        // 编码不变只改名 → 放行
        Warehouse updated = service.update(first.getId(), command("WH-RAW", "一号原料仓"), OPERATOR);
        assertEquals("一号原料仓", updated.getName());
    }

    @Test
    void 名称为空被拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create(command(null, null), OPERATOR));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(command(null, "  "), OPERATOR));
    }

    @Test
    void 库位开关_默认关闭_可开启() {
        // locationEnabled 未填（null）视为不启用库位管理
        Warehouse plain = service.create(
                new WarehouseCommand(null, "原料仓", null, null, null), OPERATOR);
        assertFalse(plain.isLocationEnabled());

        Warehouse withLocation = service.create(
                new WarehouseCommand(null, "成品仓", null, null, true), OPERATOR);
        assertTrue(withLocation.isLocationEnabled());
    }

    @Test
    void 启停规则_停用再启用_重复操作被拒绝() {
        Warehouse warehouse = service.create(command(null, "原料仓"), OPERATOR);
        long id = warehouse.getId();

        Warehouse disabled = service.disable(id, "boss");
        assertEquals(ArchiveStatus.DISABLED, disabled.getStatus());
        assertEquals("boss", disabled.getUpdatedBy());
        // 重复停用 → 拒绝
        assertThrows(IllegalArgumentException.class, () -> service.disable(id, "boss"));

        Warehouse enabled = service.enable(id, OPERATOR);
        assertEquals(ArchiveStatus.ENABLED, enabled.getStatus());
        // 重复启用 → 拒绝
        assertThrows(IllegalArgumentException.class, () -> service.enable(id, OPERATOR));
    }

    // ---------------------------------------------------------------- 停用前库存占用检查（M3-T01c）

    /** 固定返回值的 StockChecker 桩（仓库维度） */
    private static StockChecker warehouseStock(boolean hasStock) {
        return new StockChecker() {
            @Override
            public boolean warehouseHasStock(long warehouseId) {
                return hasStock;
            }

            @Override
            public boolean productHasStock(long productId) {
                return false;
            }
        };
    }

    private WarehouseService serviceWithStockChecker(StockChecker stockChecker) {
        return new WarehouseService(warehouseRepository,
                new DefaultDocumentNumberGenerator(new InMemorySequenceProvider(), FIXED_CLOCK),
                stockChecker);
    }

    @Test
    void 停用前检查_存在非零库存余额被拒_状态不变() {
        WarehouseService guarded = serviceWithStockChecker(warehouseStock(true));
        Warehouse warehouse = guarded.create(command(null, "原料仓"), OPERATOR);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> guarded.disable(warehouse.getId(), OPERATOR));
        assertTrue(e.getMessage().contains("非零库存"));
        assertEquals(ArchiveStatus.ENABLED, guarded.get(warehouse.getId()).getStatus());
    }

    @Test
    void 停用前检查_无库存放行() {
        WarehouseService guarded = serviceWithStockChecker(warehouseStock(false));
        Warehouse warehouse = guarded.create(command(null, "原料仓"), OPERATOR);

        assertEquals(ArchiveStatus.DISABLED, guarded.disable(warehouse.getId(), OPERATOR).getStatus());
    }

    @Test
    void 停用前检查_未装配StockChecker时跳过检查_兼容旧装配() {
        // setUp 中的 service 用两参构造（stockChecker=null），停用不受库存检查影响
        Warehouse warehouse = service.create(command(null, "原料仓"), OPERATOR);
        assertEquals(ArchiveStatus.DISABLED, service.disable(warehouse.getId(), OPERATOR).getStatus());
    }

    @Test
    void 查询不存在的仓库抛404异常() {
        assertThrows(WarehouseNotFoundException.class, () -> service.get(999L));
    }

    @Test
    void 分页关键字查询_匹配编码名称负责人() {
        service.create(command("WH-RAW", "原料仓"), OPERATOR);
        service.create(new WarehouseCommand("WH-FIN", "成品仓", null, "老李", false), OPERATOR);

        PageResult<Warehouse> byName = service.search(new WarehouseQuery("原料", null, 1, 20));
        assertEquals(1, byName.total());
        PageResult<Warehouse> byCode = service.search(new WarehouseQuery("WH-FIN", null, 1, 20));
        assertEquals(1, byCode.total());
        PageResult<Warehouse> byManager = service.search(new WarehouseQuery("老李", null, 1, 20));
        assertEquals(1, byManager.total());
        PageResult<Warehouse> all = service.search(new WarehouseQuery(null, null, 1, 20));
        assertEquals(2, all.total());
    }

    @Test
    void 分页关键字查询_可按状态过滤() {
        Warehouse first = service.create(command("WH-RAW", "原料仓"), OPERATOR);
        service.create(command("WH-FIN", "成品仓"), OPERATOR);
        service.disable(first.getId(), OPERATOR);

        PageResult<Warehouse> enabled = service.search(new WarehouseQuery(null, ArchiveStatus.ENABLED, 1, 20));
        assertEquals(1, enabled.total());
        assertEquals("成品仓", enabled.items().get(0).getName());
        PageResult<Warehouse> disabled = service.search(new WarehouseQuery(null, ArchiveStatus.DISABLED, 1, 20));
        assertEquals(1, disabled.total());
    }

    @Test
    void 审计字段完整() {
        Warehouse warehouse = service.create(command(null, "原料仓"), OPERATOR);
        assertEquals(OPERATOR, warehouse.getCreatedBy());
        assertNotNull(warehouse.getCreatedAt());
        assertEquals(OPERATOR, warehouse.getUpdatedBy());
        assertNotNull(warehouse.getUpdatedAt());
    }

    /**
     * warehouse 领域测试用内存仓储替身（仅测试使用，不进生产）。
     */
    static final class InMemoryWarehouseRepository implements WarehouseRepository {
        final Map<Long, Warehouse> store = new LinkedHashMap<>();
        private final AtomicLong idGen = new AtomicLong();

        @Override
        public void save(Warehouse warehouse) {
            if (warehouse.getId() == null) {
                warehouse.assignId(idGen.incrementAndGet());
            }
            store.put(warehouse.getId(), warehouse);
        }

        @Override
        public Optional<Warehouse> findById(long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public boolean existsByCode(String code) {
            return store.values().stream().anyMatch(w -> w.getCode().equals(code));
        }

        @Override
        public PageResult<Warehouse> search(WarehouseQuery query) {
            List<Warehouse> matched = store.values().stream()
                    .filter(w -> query.status() == null || w.getStatus() == query.status())
                    .filter(w -> matchesKeyword(w, query.keyword()))
                    .sorted(Comparator.comparing(Warehouse::getId).reversed())
                    .toList();
            int from = Math.min((query.page() - 1) * query.size(), matched.size());
            int to = Math.min(from + query.size(), matched.size());
            return new PageResult<>(new ArrayList<>(matched.subList(from, to)),
                    matched.size(), query.page(), query.size());
        }

        private static boolean matchesKeyword(Warehouse w, String keyword) {
            if (keyword == null) {
                return true;
            }
            String kw = keyword.toLowerCase(Locale.ROOT);
            return w.getCode().toLowerCase(Locale.ROOT).contains(kw)
                    || w.getName().toLowerCase(Locale.ROOT).contains(kw)
                    || (w.getManager() != null && w.getManager().toLowerCase(Locale.ROOT).contains(kw));
        }
    }
}
