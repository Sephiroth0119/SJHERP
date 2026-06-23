package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.util.Objects;

import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 月末成本结转单行（从属 {@link ProductionCostSettlement} 聚合，M5-T06）。
 *
 * <p>每工单一行，承载该工单本期归集的料/工/费三要素、约当产量法分摊出的完工/在产成本，
 * 以及防重复入账锚点（{@code alreadyTransferred}）与过账回填字段（{@code costAdjustIdemKey}/
 * {@code voucherDocNo}）。
 *
 * <h2>约当产量法（ADR §2 / R-T06-5）</h2>
 * 料按数量 100% 投入随完工入库结转（T05 已入，T06 读不改，不计约当量）；工费按统一完工程度
 * 百分比折算约当量：
 * <pre>
 *   在产约当量 = wipQty × wipCompletionPct / 100
 *   总约当产量 = completedQty + 在产约当量
 *   单位工费   = (laborCost + overheadCost) / 总约当产量
 *   完工应负担工费 = (laborCost + overheadCost) − 在产应负担工费   （尾差并入完工，R-T06-5）
 *   在产应负担工费 = 在产约当量 × 单位工费
 * </pre>
 *
 * <p>原则 5：金额/数量一律 {@link BigDecimal}，禁 float/double。
 */
public final class ProductionCostSettlementLine {

    /** 数据库自增主键（持久化后回填） */
    private Long id;

    /** 行号（单据内从 1 起） */
    private final int lineNo;

    /** 工单号（每工单一行） */
    private final String workOrderDocNo;

    /** 本期料成本（Σ COMPLETED 领料 issuedCost，T05 口径，2 位） */
    private final BigDecimal materialCost;

    /** 本期人工成本（Σ 报工 reportedHours × 工序 costRate / 默认人工费率，2 位） */
    private final BigDecimal laborCost;

    /** 本期制造费用（Σ 报工 reportedHours × 制造费用率，2 位） */
    private final BigDecimal overheadCost;

    /** 本期完工入库数量（来自工单已过账报工累计完工 − 前期已结转完工量，6 位） */
    private final BigDecimal completedQty;

    /** 完工应负担成本 = 完工料 + 完工应负担工费（2 位；料随完工 100% 结转，故完工料 = materialCost 完工部分） */
    private final BigDecimal completedCost;

    /** 期末在产数量（6 位） */
    private final BigDecimal wipQty;

    /** 完工程度百分比（0–100，2 位；工单级单一百分比，R-T06-3） */
    private final BigDecimal wipCompletionPct;

    /** 期末在产应负担工费（2 位；料 100% 投入随完工结转，在产不含料的工费，ADR §2.1） */
    private final BigDecimal wipCost;

    /** 前期已结转完工工费锚点（防分批跨月重复入账，照 T05，2 位） */
    private final BigDecimal alreadyTransferred;

    /** COST_ADJUST 幂等键（过账后回填，PRODUCTION_COST_SETTLEMENT:PC单号:行号） */
    private String costAdjustIdemKey;

    /** GL 凭证号（过账后回填，每工单一组料/工费/完工结转凭证的凭证号） */
    private String voucherDocNo;

    private ProductionCostSettlementLine(Long id, int lineNo, String workOrderDocNo,
                                         BigDecimal materialCost, BigDecimal laborCost,
                                         BigDecimal overheadCost, BigDecimal completedQty,
                                         BigDecimal completedCost, BigDecimal wipQty,
                                         BigDecimal wipCompletionPct, BigDecimal wipCost,
                                         BigDecimal alreadyTransferred, String costAdjustIdemKey,
                                         String voucherDocNo) {
        this.id = id;
        this.lineNo = lineNo;
        this.workOrderDocNo = Objects.requireNonNull(workOrderDocNo, "工单号不能为空");
        this.materialCost = scale(materialCost);
        this.laborCost = scale(laborCost);
        this.overheadCost = scale(overheadCost);
        this.completedQty = Objects.requireNonNull(completedQty, "完工数量不能为空");
        this.completedCost = scale(completedCost);
        this.wipQty = wipQty != null ? wipQty : BigDecimal.ZERO;
        this.wipCompletionPct = wipCompletionPct != null ? wipCompletionPct : BigDecimal.ZERO;
        this.wipCost = scale(wipCost);
        this.alreadyTransferred = scale(alreadyTransferred);
        this.costAdjustIdemKey = costAdjustIdemKey;
        this.voucherDocNo = voucherDocNo;
    }

    /**
     * 建单工厂（成本字段由 {@link ProductionCostSettlementService} 经约当产量法算出后传入）。
     */
    public static ProductionCostSettlementLine create(int lineNo, String workOrderDocNo,
                                                      BigDecimal materialCost, BigDecimal laborCost,
                                                      BigDecimal overheadCost, BigDecimal completedQty,
                                                      BigDecimal completedCost, BigDecimal wipQty,
                                                      BigDecimal wipCompletionPct, BigDecimal wipCost,
                                                      BigDecimal alreadyTransferred) {
        if (lineNo < 1) {
            throw new IllegalArgumentException("成本结转单行号必须 >= 1: " + lineNo);
        }
        return new ProductionCostSettlementLine(null, lineNo, workOrderDocNo, materialCost,
                laborCost, overheadCost, completedQty, completedCost, wipQty, wipCompletionPct,
                wipCost, alreadyTransferred, null, null);
    }

    /** 持久层重建工厂（不重跑业务校验）。 */
    public static ProductionCostSettlementLine restore(long id, int lineNo, String workOrderDocNo,
                                                       BigDecimal materialCost, BigDecimal laborCost,
                                                       BigDecimal overheadCost, BigDecimal completedQty,
                                                       BigDecimal completedCost, BigDecimal wipQty,
                                                       BigDecimal wipCompletionPct, BigDecimal wipCost,
                                                       BigDecimal alreadyTransferred,
                                                       String costAdjustIdemKey, String voucherDocNo) {
        return new ProductionCostSettlementLine(id, lineNo, workOrderDocNo, materialCost, laborCost,
                overheadCost, completedQty, completedCost, wipQty, wipCompletionPct, wipCost,
                alreadyTransferred, costAdjustIdemKey, voucherDocNo);
    }

    /** 仓储落库后回填自增 id（只允许一次）。 */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("成本结转行 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    /**
     * 过账后本期实际追加到产成品的完工工费增量 = 完工应负担工费 − alreadyTransferred。
     *
     * <p>完工应负担工费 = completedCost − 完工料部分。料 100% 随完工结转，T05 已入产成品，
     * 故 T06 只追加工费部分；完工料部分 = materialCost（在产不含料），完工工费 = completedCost − materialCost。
     * 增量 = 完工工费 − 前期已结转工费（alreadyTransferred）。
     */
    public BigDecimal completedLaborOverhead() {
        return completedCost.subtract(materialCost).setScale(CostingStrategy.AMOUNT_SCALE,
                CostingStrategy.ROUNDING);
    }

    /** 本期应追加到产成品的完工工费增量（= 完工工费 − 前期已结转，照 T05 增量防重复）。 */
    public BigDecimal incrementalLaborOverhead() {
        return completedLaborOverhead().subtract(alreadyTransferred)
                .setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }

    /** 过账后回填 COST_ADJUST 幂等键。 */
    public void assignCostAdjustIdemKey(String key) {
        this.costAdjustIdemKey = key;
    }

    /** 过账后回填 GL 凭证号。 */
    public void assignVoucherDocNo(String voucherDocNo) {
        this.voucherDocNo = voucherDocNo;
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }

    public Long getId() { return id; }
    public int getLineNo() { return lineNo; }
    public String getWorkOrderDocNo() { return workOrderDocNo; }
    public BigDecimal getMaterialCost() { return materialCost; }
    public BigDecimal getLaborCost() { return laborCost; }
    public BigDecimal getOverheadCost() { return overheadCost; }
    public BigDecimal getCompletedQty() { return completedQty; }
    public BigDecimal getCompletedCost() { return completedCost; }
    public BigDecimal getWipQty() { return wipQty; }
    public BigDecimal getWipCompletionPct() { return wipCompletionPct; }
    public BigDecimal getWipCost() { return wipCost; }
    public BigDecimal getAlreadyTransferred() { return alreadyTransferred; }
    public String getCostAdjustIdemKey() { return costAdjustIdemKey; }
    public String getVoucherDocNo() { return voucherDocNo; }
}
