package com.sjherp.infra.persistence.production;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.WorkOrder;
import com.sjherp.domain.production.WorkOrderQuery;
import com.sjherp.domain.production.WorkOrderSourceType;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

/**
 * {@link JdbcWorkOrderRepository} 真库集成测试（M5-T03）。
 *
 * <p>使用 {@link MySqlContainerTestBase} 提供的共享 MySQL 8.4 容器（Flyway 全量迁移已完成）。
 * 每个测试方法使用 {@code uniqueSuffix()} 生成唯一 productId/unitId，
 * 避免同一容器内不同测试触发唯一键冲突。
 *
 * <p>work_order 表无 FK 到 product/unit 表（逻辑外键），无需预建档案行。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>insert + findByDocNo → 所有字段完整落库与回读（包括 null 可选字段）；</li>
 *   <li>update（status 流转后 save）→ status 持久化；</li>
 *   <li>search 按 productId 过滤 → 只返回目标工单；</li>
 *   <li>search 按 status 过滤 → 只返回匹配状态的工单；</li>
 *   <li>search 分页 → total/page/size/items 正确；</li>
 *   <li>findByDocNo 不存在 → 返回 empty。</li>
 * </ul>
 */
class JdbcWorkOrderRepositoryIntegrationTest extends MySqlContainerTestBase {

    private final JdbcWorkOrderRepository repository =
            new JdbcWorkOrderRepository(jdbc);

    // ================================================================ 1. insert + findByDocNo round-trip

    @Test
    void save新工单后findByDocNo_所有字段正确落库() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);
        String docNo   = "WO-IT-" + uniqueSuffix();

        WorkOrder wo = WorkOrder.create(
                docNo, productId,
                new BigDecimal("50.000000"), unitId,
                1, 2, 10L,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                "集成测试备注",
                "alice");

        repository.save(wo);

        // insert 后 id 已被回填
        assertThat(wo.getId()).isNotNull().isPositive();

        Optional<WorkOrder> found = repository.findByDocNo(docNo);

        assertThat(found).isPresent();
        WorkOrder loaded = found.get();
        assertThat(loaded.getId()).isEqualTo(wo.getId());
        assertThat(loaded.getDocNo()).isEqualTo(docNo);
        assertThat(loaded.getProductId()).isEqualTo(productId);
        assertThat(loaded.getPlannedQty()).isEqualByComparingTo(new BigDecimal("50.000000"));
        assertThat(loaded.getUnitId()).isEqualTo(unitId);
        assertThat(loaded.getCompletedQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(loaded.getBomVersion()).isEqualTo(1);
        assertThat(loaded.getRoutingVersion()).isEqualTo(2);
        assertThat(loaded.getWarehouseId()).isEqualTo(10L);
        assertThat(loaded.getMrpRunDocNo()).isNull();
        assertThat(loaded.getSourceType()).isEqualTo(WorkOrderSourceType.MANUAL);
        assertThat(loaded.getPlannedStartDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(loaded.getPlannedEndDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(loaded.getRemark()).isEqualTo("集成测试备注");
        assertThat(loaded.getStatus()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(loaded.getCreatedBy()).isEqualTo("alice");
    }

    @Test
    void save工单_可选字段全为null_正确落库() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);
        String docNo   = "WO-IT-" + uniqueSuffix();

        WorkOrder wo = WorkOrder.create(
                docNo, productId,
                new BigDecimal("10.000000"), unitId,
                null, null, null,
                null, null, null,
                "bob");

        repository.save(wo);
        assertThat(wo.getId()).isNotNull();

        Optional<WorkOrder> found = repository.findByDocNo(docNo);
        assertThat(found).isPresent();
        WorkOrder loaded = found.get();
        assertThat(loaded.getBomVersion()).isNull();
        assertThat(loaded.getRoutingVersion()).isNull();
        assertThat(loaded.getWarehouseId()).isNull();
        assertThat(loaded.getMrpRunDocNo()).isNull();
        assertThat(loaded.getPlannedStartDate()).isNull();
        assertThat(loaded.getPlannedEndDate()).isNull();
        assertThat(loaded.getRemark()).isNull();
    }

    // ================================================================ 2. update（状态流转后 save）

    @Test
    void release工单后save_status持久化为APPROVED() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);
        String docNo   = "WO-IT-" + uniqueSuffix();

        WorkOrder wo = WorkOrder.create(
                docNo, productId, new BigDecimal("20.000000"), unitId,
                null, null, null, null, null, null, "charlie");
        repository.save(wo);
        assertThat(wo.getId()).isNotNull();

        // 状态流转
        wo.release("charlie");
        repository.save(wo);

        Optional<WorkOrder> found = repository.findByDocNo(docNo);
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(DocumentStatus.APPROVED);
    }

    @Test
    void 完整状态链路_DRAFT_APPROVED_EXECUTING_COMPLETED_全部持久化() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);
        String docNo   = "WO-IT-" + uniqueSuffix();

        WorkOrder wo = WorkOrder.create(
                docNo, productId, new BigDecimal("5.000000"), unitId,
                null, null, null, null, null, null, "dave");
        repository.save(wo);

        wo.release("dave");
        repository.save(wo);
        assertThat(repository.findByDocNo(docNo).get().getStatus()).isEqualTo(DocumentStatus.APPROVED);

        wo.start("dave");
        repository.save(wo);
        assertThat(repository.findByDocNo(docNo).get().getStatus()).isEqualTo(DocumentStatus.EXECUTING);

        wo.complete("dave");
        repository.save(wo);
        assertThat(repository.findByDocNo(docNo).get().getStatus()).isEqualTo(DocumentStatus.COMPLETED);
    }

    // ================================================================ 3. findByDocNo 不存在

    @Test
    void findByDocNo_不存在_返回empty() {
        Optional<WorkOrder> result = repository.findByDocNo("WO-NOT-EXIST-99999");
        assertThat(result).isEmpty();
    }

    // ================================================================ 4. search 过滤与分页

    @Test
    void search_按productId过滤_只返回目标工单() {
        long targetProductId = Long.parseLong(uniqueSuffix(), 36);
        long otherProductId  = Long.parseLong(uniqueSuffix(), 36);
        long unitId          = Long.parseLong(uniqueSuffix(), 36);

        // 插入目标商品 2 张工单
        WorkOrder wo1 = WorkOrder.create("WO-IT-" + uniqueSuffix(), targetProductId,
                new BigDecimal("10"), unitId, null, null, null, null, null, null, "tester");
        WorkOrder wo2 = WorkOrder.create("WO-IT-" + uniqueSuffix(), targetProductId,
                new BigDecimal("20"), unitId, null, null, null, null, null, null, "tester");
        // 插入干扰商品 1 张工单
        WorkOrder woOther = WorkOrder.create("WO-IT-" + uniqueSuffix(), otherProductId,
                new BigDecimal("30"), unitId, null, null, null, null, null, null, "tester");
        repository.save(wo1);
        repository.save(wo2);
        repository.save(woOther);

        PageResult<WorkOrder> result = repository.search(
                new WorkOrderQuery(targetProductId, null, 1, 20));

        assertThat(result.total()).isEqualTo(2L);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).allMatch(w -> w.getProductId() == targetProductId);
    }

    @Test
    void search_按status过滤_只返回匹配状态的工单() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);

        // 插入 2 张 DRAFT 工单和 1 张 APPROVED 工单
        WorkOrder draft1 = WorkOrder.create("WO-IT-" + uniqueSuffix(), productId,
                new BigDecimal("10"), unitId, null, null, null, null, null, null, "tester");
        WorkOrder draft2 = WorkOrder.create("WO-IT-" + uniqueSuffix(), productId,
                new BigDecimal("10"), unitId, null, null, null, null, null, null, "tester");
        WorkOrder approved = WorkOrder.create("WO-IT-" + uniqueSuffix(), productId,
                new BigDecimal("10"), unitId, null, null, null, null, null, null, "tester");
        repository.save(draft1);
        repository.save(draft2);
        repository.save(approved);
        approved.release("tester");
        repository.save(approved);

        // 过滤 DRAFT
        PageResult<WorkOrder> draftResult = repository.search(
                new WorkOrderQuery(productId, DocumentStatus.DRAFT, 1, 20));
        assertThat(draftResult.total()).isEqualTo(2L);
        assertThat(draftResult.items()).allMatch(w -> w.getStatus() == DocumentStatus.DRAFT);

        // 过滤 APPROVED
        PageResult<WorkOrder> approvedResult = repository.search(
                new WorkOrderQuery(productId, DocumentStatus.APPROVED, 1, 20));
        assertThat(approvedResult.total()).isEqualTo(1L);
        assertThat(approvedResult.items()).allMatch(w -> w.getStatus() == DocumentStatus.APPROVED);
    }

    @Test
    void search_分页_total正确_item数量正确() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);

        // 插入 5 张工单
        for (int i = 0; i < 5; i++) {
            WorkOrder wo = WorkOrder.create("WO-IT-" + uniqueSuffix(), productId,
                    new BigDecimal("10"), unitId, null, null, null, null, null, null, "tester");
            repository.save(wo);
        }

        // 第 1 页 size=2 → 2 条，total=5
        PageResult<WorkOrder> page1 = repository.search(
                new WorkOrderQuery(productId, null, 1, 2));
        assertThat(page1.total()).isEqualTo(5L);
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.page()).isEqualTo(1);
        assertThat(page1.size()).isEqualTo(2);

        // 第 3 页 size=2 → 1 条（最后一条）
        PageResult<WorkOrder> page3 = repository.search(
                new WorkOrderQuery(productId, null, 3, 2));
        assertThat(page3.total()).isEqualTo(5L);
        assertThat(page3.items()).hasSize(1);
    }

    @Test
    void search_无匹配_返回空结果() {
        // 极大的 productId，不会命中任何已有数据
        long nonExistentProductId = 999_999_999_999L + Long.parseLong(uniqueSuffix(), 36) % 1_000_000L;

        PageResult<WorkOrder> result = repository.search(
                new WorkOrderQuery(nonExistentProductId, null, 1, 20));

        assertThat(result.total()).isEqualTo(0L);
        assertThat(result.items()).isEmpty();
    }
}
