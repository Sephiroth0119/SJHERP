package com.sjherp.domain.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.catalog.InMemoryCatalogFixtures.InMemoryCategoryRepository;
import com.sjherp.domain.catalog.InMemoryCatalogFixtures.InMemoryProductRepository;
import com.sjherp.domain.catalog.InMemoryCatalogFixtures.InMemoryUnitRepository;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DefaultDocumentNumberGenerator;
import com.sjherp.domain.common.numbering.InMemorySequenceProvider;
import com.sjherp.domain.inventory.StockChecker;

/**
 * 商品档案领域服务测试：自动编号、编码唯一、引用完整性、启停规则。
 */
class ProductServiceTest {

    private static final String OPERATOR = "tester";

    /** 固定时钟：2026-06，自动编号应为 SKU-202606-XXXX */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-12T08:00:00Z"), ZoneOffset.UTC);

    private InMemoryProductRepository productRepository;
    private InMemoryCategoryRepository categoryRepository;
    private InMemoryUnitRepository unitRepository;
    private ProductService service;

    private long bottleUnitId;
    private long boxUnitId;
    private long categoryId;

    @BeforeEach
    void setUp() {
        productRepository = new InMemoryProductRepository();
        categoryRepository = new InMemoryCategoryRepository();
        unitRepository = new InMemoryUnitRepository();
        service = new ProductService(productRepository, categoryRepository, unitRepository,
                new DefaultDocumentNumberGenerator(new InMemorySequenceProvider(), FIXED_CLOCK));

        Unit bottle = new Unit("瓶", 0, OPERATOR);
        unitRepository.save(bottle);
        bottleUnitId = bottle.getId();
        Unit box = new Unit("箱", 0, OPERATOR);
        unitRepository.save(box);
        boxUnitId = box.getId();
        CategoryService categoryService = new CategoryService(categoryRepository, productRepository);
        categoryId = categoryService.create("饮料", null, OPERATOR).getId();
    }

    private ProductCommand command(String code, String name) {
        return new ProductCommand(code, name, "500ml", categoryId, bottleUnitId, "6901234567890",
                "测试商品", List.of(new UnitConversion(boxUnitId, new BigDecimal("12"))));
    }

    @Test
    void 编码为空时自动编号_SKU前缀年月序号() {
        Product first = service.create(command(null, "可乐"), OPERATOR);
        Product second = service.create(command("", "雪碧"), OPERATOR);
        assertEquals("SKU-202606-0001", first.getCode());
        assertEquals("SKU-202606-0002", second.getCode());
        assertNotNull(first.getId());
        assertEquals(ArchiveStatus.ENABLED, first.getStatus());
    }

    @Test
    void 手填编码可用_重复被拒绝() {
        service.create(command("COLA-001", "可乐"), OPERATOR);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(command("COLA-001", "山寨可乐"), OPERATOR));
        assertTrue(e.getMessage().contains("已存在"));
    }

    @Test
    void 更新可改编码_与他人重复被拒绝_与自己相同放行() {
        Product cola = service.create(command("COLA-001", "可乐"), OPERATOR);
        service.create(command("SPRITE-001", "雪碧"), OPERATOR);

        // 改成他人编码 → 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> service.update(cola.getId(), command("SPRITE-001", "可乐"), OPERATOR));
        // 编码不变只改名 → 放行
        Product updated = service.update(cola.getId(), command("COLA-001", "无糖可乐"), OPERATOR);
        assertEquals("无糖可乐", updated.getName());
    }

    @Test
    void 基本单位不存在被拒绝() {
        ProductCommand cmd = new ProductCommand(null, "可乐", null, null, 999L, null, null, null);
        assertThrows(CatalogNotFoundException.class, () -> service.create(cmd, OPERATOR));
    }

    @Test
    void 类目不存在被拒绝() {
        ProductCommand cmd = new ProductCommand(null, "可乐", null, 999L, bottleUnitId, null, null, null);
        assertThrows(CatalogNotFoundException.class, () -> service.create(cmd, OPERATOR));
    }

    @Test
    void 换算单位不存在被拒绝() {
        ProductCommand cmd = new ProductCommand(null, "可乐", null, null, bottleUnitId, null, null,
                List.of(new UnitConversion(999L, new BigDecimal("12"))));
        assertThrows(CatalogNotFoundException.class, () -> service.create(cmd, OPERATOR));
    }

    @Test
    void 基本单位登记换算率被拒绝() {
        ProductCommand cmd = new ProductCommand(null, "可乐", null, null, bottleUnitId, null, null,
                List.of(new UnitConversion(bottleUnitId, new BigDecimal("1"))));
        assertThrows(IllegalArgumentException.class, () -> service.create(cmd, OPERATOR));
    }

    @Test
    void 换算单位重复登记被拒绝() {
        ProductCommand cmd = new ProductCommand(null, "可乐", null, null, bottleUnitId, null, null,
                List.of(new UnitConversion(boxUnitId, new BigDecimal("12")),
                        new UnitConversion(boxUnitId, new BigDecimal("24"))));
        assertThrows(IllegalArgumentException.class, () -> service.create(cmd, OPERATOR));
    }

    @Test
    void 启停规则_停用再启用_重复操作被拒绝() {
        Product product = service.create(command(null, "可乐"), OPERATOR);
        long id = product.getId();

        Product disabled = service.disable(id, "boss");
        assertEquals(ArchiveStatus.DISABLED, disabled.getStatus());
        assertEquals("boss", disabled.getUpdatedBy());
        // 重复停用 → 拒绝
        assertThrows(IllegalArgumentException.class, () -> service.disable(id, "boss"));

        Product enabled = service.enable(id, OPERATOR);
        assertEquals(ArchiveStatus.ENABLED, enabled.getStatus());
        // 重复启用 → 拒绝
        assertThrows(IllegalArgumentException.class, () -> service.enable(id, OPERATOR));
    }

    @Test
    void 查询不存在的商品抛404异常() {
        assertThrows(CatalogNotFoundException.class, () -> service.get(999L));
    }

    // ---------------------------------------------------------------- 停用前库存占用检查（M3-T01c）

    /** 固定返回值的 StockChecker 桩（商品维度） */
    private static StockChecker productStock(boolean hasStock) {
        return new StockChecker() {
            @Override
            public boolean warehouseHasStock(long warehouseId) {
                return false;
            }

            @Override
            public boolean productHasStock(long productId) {
                return hasStock;
            }
        };
    }

    private ProductService serviceWithStockChecker(StockChecker stockChecker) {
        return new ProductService(productRepository, categoryRepository, unitRepository,
                new DefaultDocumentNumberGenerator(new InMemorySequenceProvider(), FIXED_CLOCK),
                stockChecker);
    }

    @Test
    void 停用前检查_存在非零库存余额被拒_状态不变() {
        ProductService guarded = serviceWithStockChecker(productStock(true));
        Product product = guarded.create(command(null, "可乐"), OPERATOR);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> guarded.disable(product.getId(), OPERATOR));
        assertTrue(e.getMessage().contains("非零库存"));
        assertEquals(ArchiveStatus.ENABLED, guarded.get(product.getId()).getStatus());
    }

    @Test
    void 停用前检查_无库存放行() {
        ProductService guarded = serviceWithStockChecker(productStock(false));
        Product product = guarded.create(command(null, "可乐"), OPERATOR);

        assertEquals(ArchiveStatus.DISABLED, guarded.disable(product.getId(), OPERATOR).getStatus());
    }

    @Test
    void 停用前检查_未装配StockChecker时跳过检查_兼容旧装配() {
        // setUp 中的 service 用四参构造（stockChecker=null），停用不受库存检查影响
        Product product = service.create(command(null, "可乐"), OPERATOR);
        assertEquals(ArchiveStatus.DISABLED, service.disable(product.getId(), OPERATOR).getStatus());
    }

    @Test
    void 分页关键字查询_匹配编码名称条码() {
        service.create(command("COLA-001", "可口可乐"), OPERATOR);
        service.create(command("SPRITE-001", "雪碧"), OPERATOR);

        PageResult<Product> byName = service.search(new ProductQuery("可乐", null, 1, 20));
        assertEquals(1, byName.total());
        PageResult<Product> byCode = service.search(new ProductQuery("SPRITE", null, 1, 20));
        assertEquals(1, byCode.total());
        PageResult<Product> byBarcode = service.search(new ProductQuery("6901234567890", null, 1, 20));
        assertEquals(2, byBarcode.total());
        PageResult<Product> all = service.search(new ProductQuery(null, null, 1, 20));
        assertEquals(2, all.total());
    }

    @Test
    void 分页关键字查询_可按状态过滤() {
        Product cola = service.create(command("COLA-001", "可口可乐"), OPERATOR);
        service.create(command("SPRITE-001", "雪碧"), OPERATOR);
        service.disable(cola.getId(), OPERATOR);

        PageResult<Product> enabled = service.search(new ProductQuery(null, ArchiveStatus.ENABLED, 1, 20));
        assertEquals(1, enabled.total());
        assertEquals("雪碧", enabled.items().get(0).getName());
        PageResult<Product> disabled = service.search(new ProductQuery(null, ArchiveStatus.DISABLED, 1, 20));
        assertEquals(1, disabled.total());
    }

    @Test
    void 审计字段完整() {
        Product product = service.create(command(null, "可乐"), OPERATOR);
        assertEquals(OPERATOR, product.getCreatedBy());
        assertNotNull(product.getCreatedAt());
        assertEquals(OPERATOR, product.getUpdatedBy());
        assertNotNull(product.getUpdatedAt());
    }
}
