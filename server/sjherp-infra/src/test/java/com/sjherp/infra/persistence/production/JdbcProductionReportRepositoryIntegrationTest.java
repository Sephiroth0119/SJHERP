package com.sjherp.infra.persistence.production;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.ProductionReport;
import com.sjherp.domain.production.ProductionReportLine;
import com.sjherp.domain.production.ProductionReportQuery;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

/**
 * {@link JdbcProductionReportRepository} 真库集成测试（M5-T05）。
 *
 * <p>使用 {@link MySqlContainerTestBase} 提供的共享 MySQL 8.4 容器（Flyway 全量迁移已完成）。
 * 每个测试方法使用 {@code uniqueSuffix()} 生成唯一 docNo，避免同一容器内不同测试触发唯一键冲突。
 *
 * <p>production_report / production_report_line 表无 FK 到 work_order / product / warehouse 表，
 * 无需预建档案行。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>insert + findByDocNo → 头+行完整落库与回读（含可选字段 inboundCost=null / operation 字段可空）；</li>
 *   <li>过账后 inboundCost 回填 → update 后回读值正确；</li>
 *   <li>update（status 流转）→ status 持久化；</li>
 *   <li>search 按 workOrderDocNo 过滤 → 只返回目标报工单；</li>
 *   <li>search 按 status 过滤 → 只返回匹配状态；</li>
 *   <li>search 分页 → total/page/size/items 正确；</li>
 *   <li>findByDocNo 不存在 → empty；</li>
 *   <li>JdbcWorkOrderRepository.update 含 completed_qty（D11 回归）。</li>
 * </ul>
 */
class JdbcProductionReportRepositoryIntegrationTest extends MySqlContainerTestBase {

    private final JdbcProductionReportRepository repository =
            new JdbcProductionReportRepository(jdbc);

    // ================================================================ 1. insert + findByDocNo 头行 round-trip

    @Test
    void save新报工单后findByDocNo_头行字段完整落库() {
        String woDocNo = "WO-PR-IT-" + uniqueSuffix();
        String docNo   = "PR-IT-"    + uniqueSuffix();
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);

        ProductionReportLine line = ProductionReportLine.create(
                1, 10, "焊接", "A线",
                new BigDecimal("2.500000"), null, unitId);

        ProductionReport pr = ProductionReport.create(
                docNo, woDocNo, 1L, productId,
                new BigDecimal("5.000000"), new BigDecimal("0.500000"),
                unitId, "集成测试备注", List.of(line), "alice");

        repository.save(pr);

        // insert 后主键已回填
        assertThat(pr.getId()).isNotNull().isPositive();

        Optional<ProductionReport> found = repository.findByDocNo(docNo);
        assertThat(found).isPresent();
        ProductionReport loaded = found.get();

        // 头字段
        assertThat(loaded.getId()).isEqualTo(pr.getId());
        assertThat(loaded.getDocNo()).isEqualTo(docNo);
        assertThat(loaded.getWorkOrderDocNo()).isEqualTo(woDocNo);
        assertThat(loaded.getWarehouseId()).isEqualTo(1L);
        assertThat(loaded.getProductId()).isEqualTo(productId);
        assertThat(loaded.getCompletedQty()).isEqualByComparingTo("5.000000");
        assertThat(loaded.getScrapQty()).isEqualByComparingTo("0.500000");
        assertThat(loaded.getUnitId()).isEqualTo(unitId);
        assertThat(loaded.getInboundCost()).isNull();  // 未过账，成本为 null
        assertThat(loaded.getRemark()).isEqualTo("集成测试备注");
        assertThat(loaded.getStatus()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(loaded.getReversalOfId()).isNull();
        assertThat(loaded.getReversedById()).isNull();
        assertThat(loaded.getCreatedBy()).isEqualTo("alice");

        // 行字段
        assertThat(loaded.getLines()).hasSize(1);
        ProductionReportLine loadedLine = loaded.getLines().get(0);
        assertThat(loadedLine.getLineNo()).isEqualTo(1);
        assertThat(loadedLine.getOperationSeqNo()).isEqualTo(10);
        assertThat(loadedLine.getOperationName()).isEqualTo("焊接");
        assertThat(loadedLine.getWorkCenter()).isEqualTo("A线");
        assertThat(loadedLine.getReportedHours()).isEqualByComparingTo("2.500000");
        assertThat(loadedLine.getReportedQty()).isNull();  // 可空
        assertThat(loadedLine.getUnitId()).isEqualTo(unitId);
    }

    @Test
    void save多行报工单_行可选字段为null_回读正确() {
        String woDocNo = "WO-PR-IT-" + uniqueSuffix();
        String docNo   = "PR-IT-"    + uniqueSuffix();
        long unitId    = Long.parseLong(uniqueSuffix(), 36);
        long productId = Long.parseLong(uniqueSuffix(), 36);

        // 两行：第一行有 operationSeqNo/operationName/workCenter，第二行全部为 null
        ProductionReportLine line1 = ProductionReportLine.create(
                1, 1, "冲压", "B线",
                new BigDecimal("1.000000"), new BigDecimal("5.000000"), unitId);
        ProductionReportLine line2 = ProductionReportLine.create(
                2, null, null, null,
                new BigDecimal("0.500000"), null, unitId);

        ProductionReport pr = ProductionReport.create(
                docNo, woDocNo, 2L, productId,
                new BigDecimal("5.000000"), null, unitId, null,
                List.of(line1, line2), "bob");
        repository.save(pr);

        ProductionReport loaded = repository.findByDocNo(docNo).orElseThrow();
        assertThat(loaded.getLines()).hasSize(2);
        // 第一行
        assertThat(loaded.getLines().get(0).getOperationSeqNo()).isEqualTo(1);
        assertThat(loaded.getLines().get(0).getOperationName()).isEqualTo("冲压");
        assertThat(loaded.getLines().get(0).getWorkCenter()).isEqualTo("B线");
        assertThat(loaded.getLines().get(0).getReportedQty()).isEqualByComparingTo("5.000000");
        // 第二行：可空字段均为 null
        assertThat(loaded.getLines().get(1).getOperationSeqNo()).isNull();
        assertThat(loaded.getLines().get(1).getOperationName()).isNull();
        assertThat(loaded.getLines().get(1).getWorkCenter()).isNull();
        assertThat(loaded.getLines().get(1).getReportedQty()).isNull();
        // scrapQty 默认 0
        assertThat(loaded.getScrapQty()).isEqualByComparingTo("0.000000");
        // remark 可空
        assertThat(loaded.getRemark()).isNull();
    }

    // ================================================================ 2. 过账后 inboundCost 回填

    @Test
    void 过账后inboundCost回填_update后回读值正确() {
        String woDocNo = "WO-PR-IT-" + uniqueSuffix();
        String docNo   = "PR-IT-"    + uniqueSuffix();
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);

        ProductionReportLine line = ProductionReportLine.create(
                1, null, null, null,
                new BigDecimal("2.000000"), null, unitId);
        ProductionReport pr = ProductionReport.create(
                docNo, woDocNo, 1L, productId,
                new BigDecimal("5.000000"), null, unitId, null,
                List.of(line), "charlie");
        repository.save(pr);

        // 模拟过账：状态流转 + inboundCost 回填
        pr.registerEventPublisher(event -> {});
        pr.approve("charlie");
        pr.startExecution("charlie");
        pr.assignInboundCost(new BigDecimal("150.00"));
        pr.complete("charlie");
        repository.save(pr);  // update

        ProductionReport loaded = repository.findByDocNo(docNo).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(loaded.getInboundCost()).isEqualByComparingTo("150.00");
    }

    // ================================================================ 3. status 持久化

    @Test
    void approve流转后save_status持久化为APPROVED() {
        String docNo   = "PR-IT-" + uniqueSuffix();
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);

        ProductionReportLine line = ProductionReportLine.create(
                1, null, null, null,
                new BigDecimal("1.000000"), null, unitId);
        ProductionReport pr = ProductionReport.create(
                docNo, "WO-PR-IT-" + uniqueSuffix(), 1L, productId,
                new BigDecimal("1.000000"), null, unitId, null,
                List.of(line), "dave");
        repository.save(pr);

        pr.registerEventPublisher(event -> {});
        pr.approve("dave");
        repository.save(pr);

        assertThat(repository.findByDocNo(docNo).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.APPROVED);
    }

    @Test
    void cancel流转后save_status持久化为CANCELLED() {
        String docNo   = "PR-IT-" + uniqueSuffix();
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);

        ProductionReportLine line = ProductionReportLine.create(
                1, null, null, null,
                new BigDecimal("1.000000"), null, unitId);
        ProductionReport pr = ProductionReport.create(
                docNo, "WO-PR-IT-" + uniqueSuffix(), 1L, productId,
                new BigDecimal("1.000000"), null, unitId, null,
                List.of(line), "eve");
        repository.save(pr);

        pr.registerEventPublisher(event -> {});
        pr.cancel("eve");
        repository.save(pr);

        assertThat(repository.findByDocNo(docNo).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.CANCELLED);
    }

    // ================================================================ 4. findByDocNo 不存在

    @Test
    void findByDocNo_不存在_返回empty() {
        Optional<ProductionReport> result = repository.findByDocNo("PR-NOT-EXIST-99999");
        assertThat(result).isEmpty();
    }

    // ================================================================ 5. search 过滤与分页

    @Test
    void search_按workOrderDocNo过滤_只返回目标报工单() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);
        String targetWo = "WO-PR-TGT-" + uniqueSuffix();
        String otherWo  = "WO-PR-OTH-" + uniqueSuffix();

        // 目标工单下 2 张报工单
        for (int i = 0; i < 2; i++) {
            ProductionReportLine l = ProductionReportLine.create(
                    1, null, null, null, new BigDecimal("1.000000"), null, unitId);
            repository.save(ProductionReport.create(
                    "PR-IT-" + uniqueSuffix(), targetWo, 1L, productId,
                    new BigDecimal("3.000000"), null, unitId, null,
                    List.of(l), "tester"));
        }
        // 干扰工单下 1 张
        ProductionReportLine lOther = ProductionReportLine.create(
                1, null, null, null, new BigDecimal("1.000000"), null, unitId);
        repository.save(ProductionReport.create(
                "PR-IT-" + uniqueSuffix(), otherWo, 1L, productId,
                new BigDecimal("3.000000"), null, unitId, null,
                List.of(lOther), "tester"));

        PageResult<ProductionReport> result = repository.search(
                new ProductionReportQuery(targetWo, null, 1, 20));

        assertThat(result.total()).isEqualTo(2L);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).allMatch(p -> p.getWorkOrderDocNo().equals(targetWo));
    }

    @Test
    void search_按status过滤_只返回匹配状态() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);
        String woDocNo = "WO-PR-IT-" + uniqueSuffix();

        // 2 张 DRAFT + 1 张 APPROVED
        for (int i = 0; i < 2; i++) {
            ProductionReportLine l = ProductionReportLine.create(
                    1, null, null, null, new BigDecimal("1.000000"), null, unitId);
            repository.save(ProductionReport.create(
                    "PR-IT-" + uniqueSuffix(), woDocNo, 1L, productId,
                    new BigDecimal("1.000000"), null, unitId, null,
                    List.of(l), "tester"));
        }
        ProductionReportLine l3 = ProductionReportLine.create(
                1, null, null, null, new BigDecimal("1.000000"), null, unitId);
        ProductionReport prApproved = ProductionReport.create(
                "PR-IT-" + uniqueSuffix(), woDocNo, 1L, productId,
                new BigDecimal("1.000000"), null, unitId, null,
                List.of(l3), "tester");
        repository.save(prApproved);
        prApproved.registerEventPublisher(event -> {});
        prApproved.approve("tester");
        repository.save(prApproved);

        PageResult<ProductionReport> draftResult = repository.search(
                new ProductionReportQuery(woDocNo, DocumentStatus.DRAFT, 1, 20));
        assertThat(draftResult.total()).isEqualTo(2L);
        assertThat(draftResult.items()).allMatch(p -> p.getStatus() == DocumentStatus.DRAFT);

        PageResult<ProductionReport> approvedResult = repository.search(
                new ProductionReportQuery(woDocNo, DocumentStatus.APPROVED, 1, 20));
        assertThat(approvedResult.total()).isEqualTo(1L);
        assertThat(approvedResult.items()).allMatch(p -> p.getStatus() == DocumentStatus.APPROVED);
    }

    @Test
    void search_分页_total正确_item数量正确() {
        long productId = Long.parseLong(uniqueSuffix(), 36);
        long unitId    = Long.parseLong(uniqueSuffix(), 36);
        String woDocNo = "WO-PR-IT-" + uniqueSuffix();

        // 插入 5 张报工单
        for (int i = 0; i < 5; i++) {
            ProductionReportLine l = ProductionReportLine.create(
                    1, null, null, null, new BigDecimal("1.000000"), null, unitId);
            repository.save(ProductionReport.create(
                    "PR-IT-" + uniqueSuffix(), woDocNo, 1L, productId,
                    new BigDecimal("1.000000"), null, unitId, null,
                    List.of(l), "tester"));
        }

        // 第 1 页 size=2 → 2 条，total=5
        PageResult<ProductionReport> page1 = repository.search(
                new ProductionReportQuery(woDocNo, null, 1, 2));
        assertThat(page1.total()).isEqualTo(5L);
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.page()).isEqualTo(1);
        assertThat(page1.size()).isEqualTo(2);

        // 第 3 页 size=2 → 1 条（最后一条）
        PageResult<ProductionReport> page3 = repository.search(
                new ProductionReportQuery(woDocNo, null, 3, 2));
        assertThat(page3.total()).isEqualTo(5L);
        assertThat(page3.items()).hasSize(1);
    }

    @Test
    void search_无匹配_返回空结果() {
        PageResult<ProductionReport> result = repository.search(
                new ProductionReportQuery("WO-NOT-EXIST-9999", null, 1, 20));
        assertThat(result.total()).isEqualTo(0L);
        assertThat(result.items()).isEmpty();
    }

    // ================================================================ 6. D11 回归：WorkOrder.completed_qty 持久化

    @Test
    void D11_工单completed_qty更新后持久化正确() {
        // 验证 JdbcWorkOrderRepository.update 含 completed_qty 列（D11 bug fix 回归）
        JdbcWorkOrderRepository woRepo = new JdbcWorkOrderRepository(jdbc);

        String woDocNo = "WO-D11-" + uniqueSuffix();
        long productId = Long.parseLong(uniqueSuffix(), 36);

        com.sjherp.domain.production.WorkOrder wo = com.sjherp.domain.production.WorkOrder.create(
                woDocNo, productId, new BigDecimal("10"), 1L,
                null, null, null, null, null, null, "tester");
        wo.registerEventPublisher(event -> {});
        woRepo.save(wo);  // insert

        // 模拟 recordCompletion（5 件）
        wo.release("tester");
        wo.start("tester");
        wo.recordCompletion(new BigDecimal("5"), "tester");
        woRepo.save(wo);  // update（含 completed_qty=5）

        com.sjherp.domain.production.WorkOrder loaded = woRepo.findByDocNo(woDocNo).orElseThrow();
        assertThat(loaded.getCompletedQty()).isEqualByComparingTo("5");
    }
}
