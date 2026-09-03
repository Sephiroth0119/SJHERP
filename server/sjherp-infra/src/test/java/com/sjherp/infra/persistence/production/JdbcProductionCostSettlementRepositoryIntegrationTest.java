package com.sjherp.infra.persistence.production;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.ProductionCostSettlement;
import com.sjherp.domain.production.ProductionCostSettlementLine;
import com.sjherp.domain.production.ProductionCostSettlementQuery;
import com.sjherp.domain.production.ProductionCostSettlementRepository.PriorCumulative;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

/**
 * {@link JdbcProductionCostSettlementRepository} 真库集成测试（M5-T06）。
 *
 * <p>覆盖：头+行 round-trip、状态/回填字段 update、分页、voucher_doc_no 回填、
 * 工费已结转锚点（sumTransferredLaborOverheadByWorkOrder）、GL 增量锚点（priorCumulativeByWorkOrder）。
 */
class JdbcProductionCostSettlementRepositoryIntegrationTest extends MySqlContainerTestBase {

    private final JdbcProductionCostSettlementRepository repository =
            new JdbcProductionCostSettlementRepository(jdbc);

    private static ProductionCostSettlementLine line(int lineNo, String woDocNo, String material,
                                                     String labor, String overhead, String completedQty,
                                                     String completedCost, String wipQty, String wipPct,
                                                     String wipCost, String alreadyTransferred) {
        return ProductionCostSettlementLine.create(lineNo, woDocNo,
                new BigDecimal(material), new BigDecimal(labor), new BigDecimal(overhead),
                new BigDecimal(completedQty), new BigDecimal(completedCost), new BigDecimal(wipQty),
                new BigDecimal(wipPct), new BigDecimal(wipCost), new BigDecimal(alreadyTransferred));
    }

    // ================================================================ 1. round-trip

    @Test
    void save新结转单后findByDocNo_头行字段完整落库() {
        String docNo = "PC-IT-" + uniqueSuffix();
        String woDocNo = "WO-PC-" + uniqueSuffix();

        ProductionCostSettlement s = ProductionCostSettlement.create(
                docNo, "202606", "集成测试",
                List.of(line(1, woDocNo, "300.00", "200.00", "50.00", "5.000000",
                        "550.00", "0.000000", "0.00", "0.00", "0.00")),
                "alice");
        repository.save(s);
        assertThat(s.getId()).isNotNull().isPositive();

        Optional<ProductionCostSettlement> found = repository.findByDocNo(docNo);
        assertThat(found).isPresent();
        ProductionCostSettlement loaded = found.get();
        assertThat(loaded.getPeriod()).isEqualTo("202606");
        assertThat(loaded.getRemark()).isEqualTo("集成测试");
        assertThat(loaded.getStatus()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(loaded.getCreatedBy()).isEqualTo("alice");
        assertThat(loaded.getLines()).hasSize(1);
        ProductionCostSettlementLine l = loaded.getLines().get(0);
        assertThat(l.getWorkOrderDocNo()).isEqualTo(woDocNo);
        assertThat(l.getMaterialCost()).isEqualByComparingTo("300.00");
        assertThat(l.getLaborCost()).isEqualByComparingTo("200.00");
        assertThat(l.getOverheadCost()).isEqualByComparingTo("50.00");
        assertThat(l.getCompletedQty()).isEqualByComparingTo("5.000000");
        assertThat(l.getCompletedCost()).isEqualByComparingTo("550.00");
        assertThat(l.getWipCost()).isEqualByComparingTo("0.00");
        assertThat(l.getAlreadyTransferred()).isEqualByComparingTo("0.00");
        assertThat(l.getCostAdjustIdemKey()).isNull();
        assertThat(l.getVoucherDocNo()).isNull();
    }

    // ================================================================ 2. 过账回填 + 状态 update

    @Test
    void 过账后回填idemKey与voucherDocNo_status_update后回读正确() {
        String docNo = "PC-IT-" + uniqueSuffix();
        String woDocNo = "WO-PC-" + uniqueSuffix();

        ProductionCostSettlement s = ProductionCostSettlement.create(
                docNo, "202606", null,
                List.of(line(1, woDocNo, "300.00", "200.00", "50.00", "5.000000",
                        "550.00", "0.000000", "0.00", "0.00", "0.00")),
                "bob");
        repository.save(s);

        // 模拟过账
        s.registerEventPublisher(e -> {});
        s.approve("bob");
        s.startExecution("bob");
        s.getLines().get(0).assignCostAdjustIdemKey("PRODUCTION_COST_SETTLEMENT:" + docNo + ":1");
        s.getLines().get(0).assignVoucherDocNo("VCH-202606-9999");
        s.complete("bob");
        repository.save(s);

        ProductionCostSettlement loaded = repository.findByDocNo(docNo).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(loaded.getLines().get(0).getCostAdjustIdemKey())
                .isEqualTo("PRODUCTION_COST_SETTLEMENT:" + docNo + ":1");
        assertThat(loaded.getLines().get(0).getVoucherDocNo()).isEqualTo("VCH-202606-9999");
    }

    // ================================================================ 3. 锚点查询

    @Test
    void sumTransferredLaborOverhead_仅统计COMPLETED结转行() {
        String woDocNo = "WO-PC-ANCHOR-" + uniqueSuffix();

        // COMPLETED 结转单：完工工费 = completed_cost 550 − material 300 = 250
        ProductionCostSettlement done = ProductionCostSettlement.create(
                "PC-IT-" + uniqueSuffix(), "202606", null,
                List.of(line(1, woDocNo, "300.00", "200.00", "50.00", "5.000000",
                        "550.00", "0.000000", "0.00", "0.00", "0.00")),
                "op");
        repository.save(done);
        done.registerEventPublisher(e -> {});
        done.approve("op"); done.startExecution("op"); done.complete("op");
        repository.save(done);

        // DRAFT 结转单（不应计入锚点）
        ProductionCostSettlement draft = ProductionCostSettlement.create(
                "PC-IT-" + uniqueSuffix(), "202607", null,
                List.of(line(1, woDocNo, "400.00", "300.00", "60.00", "8.000000",
                        "760.00", "0.000000", "0.00", "0.00", "250.00")),
                "op");
        repository.save(draft);

        assertThat(repository.sumTransferredLaborOverheadByWorkOrder(woDocNo))
                .isEqualByComparingTo("250.00");

        // GL 增量锚点：排除自身后，priorCumulative 应只含 done 的累计
        PriorCumulative prior = repository.priorCumulativeByWorkOrder(woDocNo, draft.getDocNo());
        assertThat(prior.materialCost()).isEqualByComparingTo("300.00");
        assertThat(prior.laborCost()).isEqualByComparingTo("200.00");
        assertThat(prior.overheadCost()).isEqualByComparingTo("50.00");
        assertThat(prior.completedCost()).isEqualByComparingTo("550.00");

        // 排除 done 自身 → 无其他 COMPLETED 行 → 全 0
        PriorCumulative self = repository.priorCumulativeByWorkOrder(woDocNo, done.getDocNo());
        assertThat(self.completedCost()).isEqualByComparingTo("0.00");
    }

    // ================================================================ 4. 分页/过滤

    @Test
    void search_按账期过滤与分页() {
        String period = "20" + uniqueSuffix().substring(0, 4);
        for (int i = 0; i < 3; i++) {
            repository.save(ProductionCostSettlement.create(
                    "PC-IT-" + uniqueSuffix(), period, null,
                    List.of(line(1, "WO-" + uniqueSuffix(), "100.00", "0.00", "0.00",
                            "1.000000", "100.00", "0.000000", "0.00", "0.00", "0.00")),
                    "op"));
        }
        PageResult<ProductionCostSettlement> page1 = repository.search(
                new ProductionCostSettlementQuery(period, null, 1, 2));
        assertThat(page1.total()).isEqualTo(3L);
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.items()).allMatch(s -> s.getPeriod().equals(period));

        PageResult<ProductionCostSettlement> page2 = repository.search(
                new ProductionCostSettlementQuery(period, null, 2, 2));
        assertThat(page2.items()).hasSize(1);
    }

    @Test
    void findByDocNo_不存在_返回empty() {
        assertThat(repository.findByDocNo("PC-NOT-EXIST-99999")).isEmpty();
    }
}
