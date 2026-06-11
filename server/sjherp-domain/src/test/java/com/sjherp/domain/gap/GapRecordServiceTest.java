package com.sjherp.domain.gap;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DefaultDocumentNumberGenerator;
import com.sjherp.domain.common.numbering.InMemorySequenceProvider;

/**
 * 流程缺口领域服务测试：自动编号、必填校验、状态流转合法性、按状态/模块分页查询。
 */
class GapRecordServiceTest {

    private static final String OPERATOR = "agent:anonymous";

    /** 固定时钟：2026-06，自动编号应为 GAP-202606-XXXX */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-12T08:00:00Z"), ZoneOffset.UTC);

    private InMemoryGapRecordRepository repository;
    private GapRecordService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryGapRecordRepository();
        service = new GapRecordService(repository,
                new DefaultDocumentNumberGenerator(new InMemorySequenceProvider(), FIXED_CLOCK));
    }

    private GapRecordCommand command(String title, BusinessModule module, GapSeverity severity) {
        return new GapRecordCommand("session-1", title,
                "用户想给不同客户设置不同折扣等级，每月自动调整",
                "系统能维护客户折扣等级并按月自动调整",
                "缺少客户等级价/折扣策略模型与定时调整机制",
                module, severity, "anonymous");
    }

    @Test
    void 创建缺口_自动编号GAP前缀年月序号_初始状态NEW() {
        GapRecord first = service.create(command("客户折扣等级", BusinessModule.SALES, GapSeverity.MEDIUM), OPERATOR);
        GapRecord second = service.create(command("批次保质期预警", BusinessModule.INVENTORY, GapSeverity.LOW), OPERATOR);

        assertEquals("GAP-202606-0001", first.getGapNo());
        assertEquals("GAP-202606-0002", second.getGapNo());
        assertNotNull(first.getId());
        assertEquals(GapStatus.NEW, first.getStatus());
        assertEquals("session-1", first.getSessionId());
        assertEquals("anonymous", first.getReporter());
        assertEquals(OPERATOR, first.getCreatedBy());
    }

    @Test
    void 必填字段为空被拒绝() {
        // 标题为空
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new GapRecordCommand("s", " ", "场景", "期望", "缺失能力",
                        BusinessModule.GENERAL, GapSeverity.LOW, "u"), OPERATOR));
        // 场景为空
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new GapRecordCommand("s", "标题", null, "期望", "缺失能力",
                        BusinessModule.GENERAL, GapSeverity.LOW, "u"), OPERATOR));
        // 期望为空
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new GapRecordCommand("s", "标题", "场景", "", "缺失能力",
                        BusinessModule.GENERAL, GapSeverity.LOW, "u"), OPERATOR));
        // 缺失能力为空
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new GapRecordCommand("s", "标题", "场景", "期望", null,
                        BusinessModule.GENERAL, GapSeverity.LOW, "u"), OPERATOR));
        // 模块为空
        assertThrows(NullPointerException.class, () -> service.create(
                new GapRecordCommand("s", "标题", "场景", "期望", "缺失能力",
                        null, GapSeverity.LOW, "u"), OPERATOR));
        // 严重度为空
        assertThrows(NullPointerException.class, () -> service.create(
                new GapRecordCommand("s", "标题", "场景", "期望", "缺失能力",
                        BusinessModule.GENERAL, null, "u"), OPERATOR));
        // 提出人为空
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new GapRecordCommand("s", "标题", "场景", "期望", "缺失能力",
                        BusinessModule.GENERAL, GapSeverity.LOW, " "), OPERATOR));
    }

    @Test
    void 超长字段被拒绝() {
        String longTitle = "标".repeat(201);
        assertThrows(IllegalArgumentException.class,
                () -> service.create(command(longTitle, BusinessModule.SALES, GapSeverity.LOW), OPERATOR));
    }

    @Test
    void sessionId可空_空白视为null() {
        GapRecord record = service.create(new GapRecordCommand("  ", "标题", "场景", "期望",
                "缺失能力", BusinessModule.GENERAL, GapSeverity.LOW, "dev"), OPERATOR);
        assertEquals(null, record.getSessionId());
    }

    @Test
    void 合法状态流转_完整路径到RESOLVED() {
        GapRecord record = service.create(command("客户折扣等级", BusinessModule.SALES, GapSeverity.MEDIUM), OPERATOR);
        long id = record.getId();

        assertEquals(GapStatus.TRIAGED, service.transitionStatus(id, GapStatus.TRIAGED, "dev").getStatus());
        assertEquals(GapStatus.IN_DEVELOPMENT, service.transitionStatus(id, GapStatus.IN_DEVELOPMENT, "dev").getStatus());
        GapRecord resolved = service.transitionStatus(id, GapStatus.RESOLVED, "dev");
        assertEquals(GapStatus.RESOLVED, resolved.getStatus());
        assertEquals("dev", resolved.getUpdatedBy());
    }

    @Test
    void 非法状态流转被拒绝_且状态不变() {
        GapRecord record = service.create(command("客户折扣等级", BusinessModule.SALES, GapSeverity.MEDIUM), OPERATOR);
        long id = record.getId();

        // NEW 不能直接 RESOLVED
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.transitionStatus(id, GapStatus.RESOLVED, "dev"));
        assertTrue(e.getMessage().contains("不允许"));
        assertEquals(GapStatus.NEW, service.get(id).getStatus());

        // 驳回后是终态，任何流转被拒
        service.transitionStatus(id, GapStatus.REJECTED, "dev");
        assertThrows(IllegalArgumentException.class,
                () -> service.transitionStatus(id, GapStatus.TRIAGED, "dev"));
    }

    @Test
    void 不存在的缺口_查询与流转都抛NotFound() {
        assertThrows(GapRecordNotFoundException.class, () -> service.get(999L));
        assertThrows(GapRecordNotFoundException.class,
                () -> service.transitionStatus(999L, GapStatus.TRIAGED, "dev"));
    }

    @Test
    void 按状态与模块分页查询() {
        service.create(command("销售缺口A", BusinessModule.SALES, GapSeverity.MEDIUM), OPERATOR);
        service.create(command("销售缺口B", BusinessModule.SALES, GapSeverity.LOW), OPERATOR);
        GapRecord finance = service.create(command("财务缺口", BusinessModule.FINANCE, GapSeverity.HIGH), OPERATOR);
        service.transitionStatus(finance.getId(), GapStatus.TRIAGED, "dev");

        // 按模块过滤
        PageResult<GapRecord> sales = service.search(new GapRecordQuery(null, BusinessModule.SALES, 1, 20));
        assertEquals(2, sales.total());

        // 按状态过滤
        PageResult<GapRecord> triaged = service.search(new GapRecordQuery(GapStatus.TRIAGED, null, 1, 20));
        assertEquals(1, triaged.total());
        assertEquals("财务缺口", triaged.items().get(0).getTitle());

        // 状态+模块组合过滤
        PageResult<GapRecord> newSales = service.search(new GapRecordQuery(GapStatus.NEW, BusinessModule.SALES, 1, 20));
        assertEquals(2, newSales.total());

        // 分页：每页 1 条，最新在前
        PageResult<GapRecord> page1 = service.search(new GapRecordQuery(null, null, 1, 1));
        assertEquals(3, page1.total());
        assertEquals(1, page1.items().size());
        assertEquals("财务缺口", page1.items().get(0).getTitle());
    }

    /** 测试用内存仓储替身（仅测试使用，不进生产） */
    static final class InMemoryGapRecordRepository implements GapRecordRepository {
        final Map<Long, GapRecord> store = new LinkedHashMap<>();
        private final AtomicLong idGen = new AtomicLong();

        @Override
        public void save(GapRecord record) {
            if (record.getId() == null) {
                record.assignId(idGen.incrementAndGet());
            }
            store.put(record.getId(), record);
        }

        @Override
        public Optional<GapRecord> findById(long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public PageResult<GapRecord> search(GapRecordQuery query) {
            List<GapRecord> matched = store.values().stream()
                    .filter(r -> query.status() == null || r.getStatus() == query.status())
                    .filter(r -> query.module() == null || r.getBusinessModule() == query.module())
                    .sorted(Comparator.comparing(GapRecord::getId).reversed())
                    .toList();
            int from = Math.min((query.page() - 1) * query.size(), matched.size());
            int to = Math.min(from + query.size(), matched.size());
            return new PageResult<>(new ArrayList<>(matched.subList(from, to)),
                    matched.size(), query.page(), query.size());
        }
    }
}
