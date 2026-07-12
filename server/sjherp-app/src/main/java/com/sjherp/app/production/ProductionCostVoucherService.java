package com.sjherp.app.production;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.gl.AccountingPeriodNotFoundException;
import com.sjherp.domain.gl.AccountingPeriodService;
import com.sjherp.domain.gl.VoucherLineInput;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.domain.gl.VoucherSourceType;
import com.sjherp.domain.inventory.CostingStrategy;
import com.sjherp.domain.production.ProductionCostSettlement;
import com.sjherp.domain.production.ProductionCostSettlementLine;
import com.sjherp.domain.production.ProductionCostSettlementRepository;
import com.sjherp.domain.production.ProductionCostSettlementRepository.PriorCumulative;

/**
 * 生产成本结转凭证服务（M5-T06，全项目最难财务点的 GL 出口）。
 *
 * <p>照 {@link com.sjherp.app.gl.AutoVoucherService} 范式（金额≤0 跳过 / findBySourceDocNo 幂等 /
 * ensurePeriodExists / createFromSource+post）。月末成本结转单（PC-）过账时，对<b>每个工单</b>
 * 出一组凭证（来源 {@link VoucherSourceType#PRODUCTION_COST_SETTLEMENT}、来源单据号 "PC单号:工单号"
 * 避 uk_voucher_source 冲突，物理唯一兜底每工单至多一组）。
 *
 * <h2>分录（ADR §5，金额一律取"本期增量"= 本期累计 − 前期已过账累计，与库存 CostAdjust 增量同口径）</h2>
 * <ol>
 *   <li><b>料归集</b>：借 5001 生产成本 / 贷 1403 原材料，金额 = 本期料增量；</li>
 *   <li><b>工费归集</b>：借 5001 / 贷 2211 应付职工薪酬（人工增量）；制造费用<b>两段式</b>——
 *       借 5101 制造费用 / 贷 2211（费用归集），再 借 5001 / 贷 5101（5101 月末归零）；</li>
 *   <li><b>完工结转</b>：借 1405 库存商品 / 贷 5001 生产成本，金额 = 本期完工成本增量。</li>
 * </ol>
 * 月末 WIP 无凭证：5001 借方余额 = 在产料 + 在产工费（进资产负债表"在产品"）。每条腿金额≤0 跳过
 * （VoucherLine「恰一方>0」），整组无金额（增量全 0）则不出凭证。
 *
 * <p>由 {@code ProductionCostSettlementAppService.post} 在结转单过账（库存 CostAdjust）之后、同一
 * @Transactional 内直调；账期 CLOSED 经 VoucherService.post 抛 PeriodClosedException 回滚整单。
 */
public class ProductionCostVoucherService {

    /** 5001 生产成本（COST/借） */
    private static final String ACC_PRODUCTION = "5001";
    /** 5101 制造费用（COST/借，两段式月末归零） */
    private static final String ACC_MANUFACTURING_OVERHEAD = "5101";
    /** 2211 应付职工薪酬（负债/贷） */
    private static final String ACC_PAYROLL = "2211";
    /** 1403 原材料（资产/贷，料归集出账） */
    private static final String ACC_RAW_MATERIAL = "1403";
    /** 1405 库存商品（资产/借，完工结转入账） */
    private static final String ACC_FINISHED_GOODS = "1405";

    private static final DocumentNumberRule VOUCHER_RULE = DocumentNumberRule.of("VCH");
    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");
    private static final int SCALE = CostingStrategy.AMOUNT_SCALE;
    private static final java.math.RoundingMode ROUNDING = CostingStrategy.ROUNDING;

    private final VoucherService voucherService;
    private final AccountingPeriodService accountingPeriodService;
    private final DocumentNumberGenerator numberGenerator;
    private final ProductionCostSettlementRepository settlementRepository;

    public ProductionCostVoucherService(VoucherService voucherService,
                                        AccountingPeriodService accountingPeriodService,
                                        DocumentNumberGenerator numberGenerator,
                                        ProductionCostSettlementRepository settlementRepository) {
        this.voucherService = Objects.requireNonNull(voucherService, "voucherService 不能为空");
        this.accountingPeriodService = Objects.requireNonNull(accountingPeriodService,
                "accountingPeriodService 不能为空");
        this.numberGenerator = Objects.requireNonNull(numberGenerator, "numberGenerator 不能为空");
        this.settlementRepository = Objects.requireNonNull(settlementRepository,
                "settlementRepository 不能为空");
    }

    /**
     * 为已过账（COMPLETED）的月末成本结转单逐工单生成并过账成本结转凭证，回填行 voucherDocNo。
     *
     * @param settlement 已过账的结转单
     * @param operator   操作人
     */
    public void generateForSettlement(ProductionCostSettlement settlement, String operator) {
        Objects.requireNonNull(settlement, "结转单不能为空");
        String period = settlement.getPeriod();
        boolean anyVoucher = false;

        for (ProductionCostSettlementLine line : settlement.getLines()) {
            // 本期增量 = 本期累计 − 前期已过账累计（排除本单自身，与库存 CostAdjust 增量同口径）
            PriorCumulative prior = settlementRepository.priorCumulativeByWorkOrder(
                    line.getWorkOrderDocNo(), settlement.getDocNo());
            BigDecimal rawMaterialInc = inc(line.getRawMaterialCost(), prior.rawMaterialCost());
            BigDecimal goodsMaterialInc = inc(line.getGoodsMaterialCost(), prior.goodsMaterialCost());
            BigDecimal materialInc = rawMaterialInc.add(goodsMaterialInc).setScale(SCALE, ROUNDING);
            BigDecimal laborInc = inc(line.getLaborCost(), prior.laborCost());
            BigDecimal overheadInc = inc(line.getOverheadCost(), prior.overheadCost());
            BigDecimal completedInc = inc(line.getCompletedCost(), prior.completedCost());

            // 整组无金额（增量全 ≤0）则跳过本工单（无金额无凭证）
            if (materialInc.signum() <= 0 && laborInc.signum() <= 0
                    && overheadInc.signum() <= 0 && completedInc.signum() <= 0) {
                continue;
            }

            String sourceDocNo = settlement.getDocNo() + ":" + line.getWorkOrderDocNo();
            // 幂等查重：同来源（PC单号:工单号）已有凭证则跳过（重过账/重试不重复）
            if (!voucherService.findBySourceDocNo(sourceDocNo).isEmpty()) {
                continue;
            }

            String summary = VoucherSourceType.PRODUCTION_COST_SETTLEMENT.label()
                    + " " + settlement.getDocNo() + " 工单" + line.getWorkOrderDocNo();
            List<VoucherLineInput> voucherLines = new ArrayList<>();
            // ① 料归集：借 5001 / 贷 1403（原材料）或 1405（商品类材料）
            if (rawMaterialInc.signum() > 0) {
                voucherLines.add(debit(ACC_PRODUCTION, rawMaterialInc, summary + " 原材料归集"));
                voucherLines.add(credit(ACC_RAW_MATERIAL, rawMaterialInc, summary + " 原材料归集"));
            }
            if (goodsMaterialInc.signum() > 0) {
                voucherLines.add(debit(ACC_PRODUCTION, goodsMaterialInc, summary + " 商品类材料归集"));
                voucherLines.add(credit(ACC_FINISHED_GOODS, goodsMaterialInc, summary + " 商品类材料归集"));
            }
            // ② 工费归集（人工）：借 5001 / 贷 2211
            if (laborInc.signum() > 0) {
                voucherLines.add(debit(ACC_PRODUCTION, laborInc, summary + " 人工归集"));
                voucherLines.add(credit(ACC_PAYROLL, laborInc, summary + " 人工归集"));
            }
            // ② 制造费用两段式：借 5101 / 贷 2211（归集）+ 借 5001 / 贷 5101（月末转入，5101 归零）
            if (overheadInc.signum() > 0) {
                voucherLines.add(debit(ACC_MANUFACTURING_OVERHEAD, overheadInc, summary + " 制造费用归集"));
                voucherLines.add(credit(ACC_PAYROLL, overheadInc, summary + " 制造费用归集"));
                voucherLines.add(debit(ACC_PRODUCTION, overheadInc, summary + " 制造费用转入"));
                voucherLines.add(credit(ACC_MANUFACTURING_OVERHEAD, overheadInc, summary + " 制造费用转入"));
            }
            // ③ 完工结转：借 1405 / 贷 5001
            if (completedInc.signum() > 0) {
                voucherLines.add(debit(ACC_FINISHED_GOODS, completedInc, summary + " 完工结转"));
                voucherLines.add(credit(ACC_PRODUCTION, completedInc, summary + " 完工结转"));
            }

            // 总额（取 Σ借）；理论恒 >0（已过整组金额判定），双保险
            BigDecimal totalDebit = voucherLines.stream().map(VoucherLineInput::debit)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalDebit.signum() <= 0 || voucherLines.size() < 2) {
                continue;
            }

            YearMonth yearMonth = YearMonth.parse(period, PERIOD_FORMAT);
            LocalDate voucherDate = yearMonth.atEndOfMonth();
            ensurePeriodExists(period, operator);
            String docNo = numberGenerator.generate(VOUCHER_RULE, yearMonth);
            voucherService.createFromSource(docNo, period, voucherDate, summary,
                    VoucherSourceType.PRODUCTION_COST_SETTLEMENT, sourceDocNo, voucherLines, operator);
            voucherService.post(docNo, operator);

            line.assignVoucherDocNo(docNo);
            anyVoucher = true;
        }

        // 回填行 voucherDocNo 持久化（仅当确有凭证生成）
        if (anyVoucher) {
            settlementRepository.save(settlement);
        }
    }

    private void ensurePeriodExists(String period, String operator) {
        try {
            accountingPeriodService.get(period);
        } catch (AccountingPeriodNotFoundException notFound) {
            accountingPeriodService.open(period, operator);
        }
    }

    private static BigDecimal inc(BigDecimal current, BigDecimal prior) {
        BigDecimal c = current == null ? BigDecimal.ZERO : current;
        BigDecimal p = prior == null ? BigDecimal.ZERO : prior;
        return c.subtract(p).setScale(SCALE, ROUNDING);
    }

    private static VoucherLineInput debit(String accountCode, BigDecimal amount, String summary) {
        return new VoucherLineInput(accountCode, amount.setScale(SCALE, ROUNDING), BigDecimal.ZERO, summary);
    }

    private static VoucherLineInput credit(String accountCode, BigDecimal amount, String summary) {
        return new VoucherLineInput(accountCode, BigDecimal.ZERO, amount.setScale(SCALE, ROUNDING), summary);
    }
}
