package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WorkOrderService 单元测试（M5-T03）——纯内存，无 Spring，无 DB，无 Mockito。
 *
 * <p>所有依赖端口由内部 Fake 实现代替，验证服务层业务逻辑。
 */
class WorkOrderServiceTest {

    // ================================================================ Fake 仓储

    static class FakeWorkOrderRepository implements WorkOrderRepository {
        private final Map<String, WorkOrder> byDocNo = new HashMap<>();
        private final AtomicLong idGen = new AtomicLong(1);

        @Override
        public void save(WorkOrder wo) {
            if (wo.getId() == null) {
                wo.assignId(idGen.getAndIncrement());
            }
            byDocNo.put(wo.getDocNo(), wo);
        }

        @Override
        public Optional<WorkOrder> findByDocNo(String docNo) {
            return Optional.ofNullable(byDocNo.get(docNo));
        }

        @Override
        public PageResult<WorkOrder> search(WorkOrderQuery query) {
            List<WorkOrder> all = List.copyOf(byDocNo.values());
            return new PageResult<>(all, all.size(), query.page(), query.size());
        }
    }

    static class FakeMrpRunRepository implements MrpRunRepository {
        private final Map<String, MrpRun> byDocNo = new HashMap<>();
        private final AtomicLong idGen = new AtomicLong(1);

        void add(MrpRun run) {
            if (run.getId() == null) run.assignId(idGen.getAndIncrement());
            byDocNo.put(run.getDocNo(), run);
        }

        @Override
        public void save(MrpRun run) {
            if (run.getId() == null) run.assignId(idGen.getAndIncrement());
            byDocNo.put(run.getDocNo(), run);
        }

        @Override
        public Optional<MrpRun> findByDocNo(String docNo) {
            return Optional.ofNullable(byDocNo.get(docNo));
        }

        @Override
        public PageResult<MrpRun> searchHistory(int page, int size) {
            return new PageResult<>(List.of(), 0, page, size);
        }
    }

    static class FakeBomRepository implements BillOfMaterialsRepository {
        private final Map<Long, BillOfMaterials> byProduct = new HashMap<>();
        private final AtomicLong idGen = new AtomicLong(1);

        void add(BillOfMaterials bom) {
            if (bom.getId() == null) bom.assignId(idGen.getAndIncrement());
            byProduct.put(bom.getProductId(), bom);
        }

        @Override
        public void save(BillOfMaterials bom) {
            if (bom.getId() == null) bom.assignId(idGen.getAndIncrement());
            byProduct.put(bom.getProductId(), bom);
        }

        @Override
        public Optional<BillOfMaterials> findById(long id) { return Optional.empty(); }

        @Override
        public Optional<BillOfMaterials> findByProductAndVersion(long productId, int version) {
            BillOfMaterials bom = byProduct.get(productId);
            return (bom != null && bom.getVersion() == version) ? Optional.of(bom) : Optional.empty();
        }

        @Override
        public List<BillOfMaterials> findEnabledByProductId(long productId) {
            return findActiveByProductId(productId).map(List::of).orElseGet(List::of);
        }

        @Override
        public PageResult<BillOfMaterials> search(BillOfMaterialsQuery query) {
            return new PageResult<>(List.of(), 0, 1, 20);
        }

        @Override
        public Optional<BillOfMaterials> findActiveByProductId(long productId) {
            BillOfMaterials bom = byProduct.get(productId);
            if (bom != null && bom.getStatus() == ArchiveStatus.ENABLED) return Optional.of(bom);
            return Optional.empty();
        }

        @Override
        public List<Long> findChildProductIds(long productId) { return List.of(); }

        @Override
        public boolean existsByProductAndVersion(long productId, int version) {
            return findByProductAndVersion(productId, version).isPresent();
        }
    }

    /** 计数序列单号生成器（无 DB 依赖） */
    static final DocumentNumberGenerator COUNTER_GEN = new DocumentNumberGenerator() {
        private int seq = 0;
        @Override
        public String generate(DocumentNumberRule rule) {
            return rule.getPrefix() + "-TEST-" + (++seq);
        }
        @Override
        public String generate(DocumentNumberRule rule, java.time.YearMonth yearMonth) {
            return rule.getPrefix() + "-TEST-" + (++seq);
        }
    };

    /** 无操作事件发布器 */
    static final DomainEventPublisher NOOP_PUBLISHER = event -> {};

    // ================================================================ 字段

    private FakeWorkOrderRepository woRepo;
    private FakeMrpRunRepository mrpRunRepo;
    private FakeBomRepository bomRepo;
    private WorkOrderService service;

    static final long PRODUCT_ID = 100L;
    static final long UNIT_ID = 1L;

    @BeforeEach
    void setUp() {
        woRepo = new FakeWorkOrderRepository();
        mrpRunRepo = new FakeMrpRunRepository();
        bomRepo = new FakeBomRepository();
        service = new WorkOrderService(woRepo, mrpRunRepo, bomRepo, COUNTER_GEN, NOOP_PUBLISHER);
    }

    // ================================================================ 测试案例

    @Test
    void createManual_正常参数_工单保存并返回DRAFT() {
        WorkOrder wo = service.createManual(
                PRODUCT_ID, new BigDecimal("50"), UNIT_ID,
                null, null, null, null, null, "备注", "alice");

        assertThat(wo.getId()).isNotNull();
        assertThat(wo.getDocNo()).startsWith("WO-");
        assertThat(wo.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(wo.getPlannedQty()).isEqualByComparingTo("50");
        assertThat(wo.getUnitId()).isEqualTo(UNIT_ID);
        assertThat(wo.getStatus()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(wo.getSourceType()).isEqualTo(WorkOrderSourceType.MANUAL);
        assertThat(wo.getCreatedBy()).isEqualTo("alice");

        // 已持久化到仓储
        assertThat(woRepo.findByDocNo(wo.getDocNo())).isPresent();
    }

    @Test
    void createFromSuggestion_MRP运行不存在_抛MrpRunNotFoundException() {
        assertThatThrownBy(() ->
                service.createFromSuggestion("MRP-NOTEXIST", PRODUCT_ID, "alice"))
                .isInstanceOf(MrpRunNotFoundException.class);
    }

    @Test
    void createFromSuggestion_MRP运行无对应商品建议_抛IllegalArgumentException() {
        // MRP 运行存在，但无 PRODUCT_ID 的 PRODUCTION 建议
        MrpSuggestion purchaseSuggestion = new MrpSuggestion(
                SuggestionType.PURCHASE, PRODUCT_ID, 0,
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"), UNIT_ID);
        MrpRun run = new MrpRun("MRP-001", Instant.now(), 1L, true, false, null, "tester",
                List.of(purchaseSuggestion));
        mrpRunRepo.add(run);

        assertThatThrownBy(() ->
                service.createFromSuggestion("MRP-001", PRODUCT_ID, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRODUCTION 建议");
    }

    @Test
    void createFromSuggestion_无启用BOM_抛IllegalArgumentException() {
        // MRP 运行存在，有 PRODUCTION 建议，但无启用 BOM
        MrpSuggestion suggestion = new MrpSuggestion(
                SuggestionType.PRODUCTION, PRODUCT_ID, 0,
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("90"), UNIT_ID);
        MrpRun run = new MrpRun("MRP-001", Instant.now(), 1L, true, false, null, "tester",
                List.of(suggestion));
        mrpRunRepo.add(run);
        // 故意不添加 BOM

        assertThatThrownBy(() ->
                service.createFromSuggestion("MRP-001", PRODUCT_ID, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BOM");
    }

    @Test
    void createFromSuggestion_正常_从MRP建议建立工单() {
        // 准备 MRP 运行
        MrpSuggestion suggestion = new MrpSuggestion(
                SuggestionType.PRODUCTION, PRODUCT_ID, 0,
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("90"), UNIT_ID);
        MrpRun run = new MrpRun("MRP-001", Instant.now(), 1L, true, false, null, "tester",
                List.of(suggestion));
        mrpRunRepo.add(run);

        // 准备启用 BOM（至少一行子件，子件 id 与父件不同）
        BomLine line = new BomLine(999L, BigDecimal.ONE, BigDecimal.ZERO, UNIT_ID);
        BillOfMaterials bom = new BillOfMaterials(PRODUCT_ID, 1, null, List.of(line), "system");
        bomRepo.add(bom);

        WorkOrder wo = service.createFromSuggestion("MRP-001", PRODUCT_ID, "alice");

        assertThat(wo.getId()).isNotNull();
        assertThat(wo.getSourceType()).isEqualTo(WorkOrderSourceType.MRP_SUGGESTION);
        assertThat(wo.getMrpRunDocNo()).isEqualTo("MRP-001");
        assertThat(wo.getPlannedQty()).isEqualByComparingTo("90"); // 净需求量
        assertThat(wo.getStatus()).isEqualTo(DocumentStatus.DRAFT);
    }

    @Test
    void release_DRAFT工单_变为APPROVED() {
        WorkOrder wo = service.createManual(
                PRODUCT_ID, new BigDecimal("10"), UNIT_ID,
                null, null, null, null, null, null, "alice");
        String docNo = wo.getDocNo();

        WorkOrder released = service.release(docNo, "alice");

        assertThat(released.getStatus()).isEqualTo(DocumentStatus.APPROVED);
        assertThat(woRepo.findByDocNo(docNo).get().getStatus()).isEqualTo(DocumentStatus.APPROVED);
    }

    @Test
    void start_APPROVED工单_变为EXECUTING() {
        WorkOrder wo = service.createManual(
                PRODUCT_ID, new BigDecimal("10"), UNIT_ID,
                null, null, null, null, null, null, "alice");
        service.release(wo.getDocNo(), "alice");

        WorkOrder started = service.start(wo.getDocNo(), "alice");

        assertThat(started.getStatus()).isEqualTo(DocumentStatus.EXECUTING);
    }

    @Test
    void complete_EXECUTING工单_变为COMPLETED() {
        WorkOrder wo = service.createManual(
                PRODUCT_ID, new BigDecimal("10"), UNIT_ID,
                null, null, null, null, null, null, "alice");
        service.release(wo.getDocNo(), "alice");
        service.start(wo.getDocNo(), "alice");

        WorkOrder completed = service.complete(wo.getDocNo(), "alice");

        assertThat(completed.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
    }

    @Test
    void cancel_DRAFT工单_变为CANCELLED() {
        WorkOrder wo = service.createManual(
                PRODUCT_ID, new BigDecimal("10"), UNIT_ID,
                null, null, null, null, null, null, "alice");

        WorkOrder cancelled = service.cancel(wo.getDocNo(), "alice");

        assertThat(cancelled.getStatus()).isEqualTo(DocumentStatus.CANCELLED);
    }

    @Test
    void reverse_APPROVED工单_变为REVERSED() {
        WorkOrder wo = service.createManual(
                PRODUCT_ID, new BigDecimal("10"), UNIT_ID,
                null, null, null, null, null, null, "alice");
        service.release(wo.getDocNo(), "alice");

        WorkOrder reversed = service.reverse(wo.getDocNo(), "alice");

        assertThat(reversed.getStatus()).isEqualTo(DocumentStatus.REVERSED);
    }

    @Test
    void reverse_EXECUTING工单_抛IllegalStateTransitionException() {
        WorkOrder wo = service.createManual(
                PRODUCT_ID, new BigDecimal("10"), UNIT_ID,
                null, null, null, null, null, null, "alice");
        service.release(wo.getDocNo(), "alice");
        service.start(wo.getDocNo(), "alice");

        assertThatThrownBy(() -> service.reverse(wo.getDocNo(), "alice"))
                .isInstanceOf(IllegalStateTransitionException.class);

        // 确认状态未变
        assertThat(woRepo.findByDocNo(wo.getDocNo()).get().getStatus())
                .isEqualTo(DocumentStatus.EXECUTING);
    }

    @Test
    void get_工单不存在_抛WorkOrderNotFoundException() {
        assertThatThrownBy(() -> service.get("WO-NOTEXIST"))
                .isInstanceOf(WorkOrderNotFoundException.class);
    }

    @Test
    void get_工单存在_正确返回() {
        WorkOrder wo = service.createManual(
                PRODUCT_ID, new BigDecimal("20"), UNIT_ID,
                null, null, null, null, null, null, "alice");

        WorkOrder found = service.get(wo.getDocNo());
        assertThat(found.getDocNo()).isEqualTo(wo.getDocNo());
        assertThat(found.getPlannedQty()).isEqualByComparingTo("20");
    }

    @Test
    void search_返回分页结果() {
        service.createManual(PRODUCT_ID, new BigDecimal("5"), UNIT_ID,
                null, null, null, null, null, null, "alice");
        service.createManual(PRODUCT_ID, new BigDecimal("10"), UNIT_ID,
                null, null, null, null, null, null, "alice");

        WorkOrderQuery query = new WorkOrderQuery(null, null, 1, 20);
        PageResult<WorkOrder> result = service.search(query);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).hasSize(2);
    }
}
