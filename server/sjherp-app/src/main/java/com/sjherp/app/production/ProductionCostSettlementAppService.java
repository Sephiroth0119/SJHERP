package com.sjherp.app.production;

import java.util.List;

import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.production.ProductionCostSettlement;
import com.sjherp.domain.production.ProductionCostSettlementLineInput;
import com.sjherp.domain.production.ProductionCostSettlementQuery;
import com.sjherp.domain.production.ProductionCostSettlementService;

/**
 * 月末成本结转单应用服务（M5-T06）。
 *
 * <p>直接标 {@code @Transactional}（过账做跨「库存 CostAdjust + GL 凭证 + 单据状态」编排，
 * 照 ProductionReportAppService / PeriodCloseService）。领域服务零 Spring 依赖，本类是唯一事务边界。
 * 单据编号 PC-YYYYMM-NNNN 在本类生成，不暴露给控制器层。
 *
 * <h2>过账原子性（ADR §4/§5）</h2>
 * {@link #post} 同一事务内：①领域 {@link ProductionCostSettlementService#post}
 * （APPROVED→EXECUTING→COMPLETED + 逐行 COST_ADJUST 追加完工工费增量到产成品仓）；
 * ②{@link ProductionCostVoucherService#generateForSettlement} 逐工单出 GL（料/工费归集 + 完工结转）
 * 并回填 voucherDocNo。任一步失败整事务回滚（账期 CLOSED 经凭证 post 抛 PeriodClosedException 回滚）。
 */
public class ProductionCostSettlementAppService {

    /** 成本结转单编号规则：PC-202606-0001 */
    static final DocumentNumberRule SETTLEMENT_RULE = DocumentNumberRule.of("PC");

    private final ProductionCostSettlementService domainService;
    private final ProductionCostVoucherService voucherService;
    private final DocumentNumberGenerator numberGenerator;

    public ProductionCostSettlementAppService(ProductionCostSettlementService domainService,
                                              ProductionCostVoucherService voucherService,
                                              DocumentNumberGenerator numberGenerator) {
        this.domainService = domainService;
        this.voucherService = voucherService;
        this.numberGenerator = numberGenerator;
    }

    /** 创建成本结转单（草稿），自动生成 PC- 编号。 */
    @Transactional
    public ProductionCostSettlement create(String period, String remark,
                                           List<ProductionCostSettlementLineInput> lines, String operator) {
        String docNo = numberGenerator.generate(SETTLEMENT_RULE);
        return domainService.create(docNo, period, remark, lines, operator);
    }

    /** 审核成本结转单：DRAFT → APPROVED。 */
    @Transactional
    public ProductionCostSettlement approve(String docNo, String operator) {
        return domainService.approve(docNo, operator);
    }

    /**
     * 过账成本结转单：APPROVED → COMPLETED，库存 CostAdjust 追加完工工费 + 出 GL（同一事务原子）。
     */
    @Transactional
    public ProductionCostSettlement post(String docNo, String operator) {
        ProductionCostSettlement posted = domainService.post(docNo, operator);
        voucherService.generateForSettlement(posted, operator);
        return posted;
    }

    /** 作废成本结转单：DRAFT → CANCELLED。 */
    @Transactional
    public ProductionCostSettlement cancel(String docNo, String operator) {
        return domainService.cancel(docNo, operator);
    }

    /** 按单号查询（不存在抛 ProductionCostSettlementNotFoundException → 404）。 */
    @Transactional(readOnly = true)
    public ProductionCostSettlement get(String docNo) {
        return domainService.get(docNo);
    }

    /** 分页查询。 */
    @Transactional(readOnly = true)
    public PageResult<ProductionCostSettlement> search(ProductionCostSettlementQuery query) {
        return domainService.search(query);
    }
}
