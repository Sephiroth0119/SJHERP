package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 月末成本结转单仓储接口（M5-T06）。实现由 infra 层提供。
 */
public interface ProductionCostSettlementRepository {

    /** 保存结转单（新建时插入，已存在时更新；行先删后插） */
    void save(ProductionCostSettlement settlement);

    /** 按单号查询（不存在返回 empty） */
    Optional<ProductionCostSettlement> findByDocNo(String docNo);

    /** 分页查询（支持账期/状态过滤） */
    PageResult<ProductionCostSettlement> search(ProductionCostSettlementQuery query);

    /**
     * 汇总某工单已过账（COMPLETED）结转单各行的"完工工费"之和（completed_cost − material_cost）。
     *
     * <p>这是工费"已结转"锚点：分批跨月结转时本期应追加 = 本期完工应负担工费 − 本值，
     * 防止重复入账（照 T05 increment 模式，R-T06-7）。无记录返回 0（非 null）。
     */
    BigDecimal sumTransferredLaborOverheadByWorkOrder(String workOrderDocNo);

    /**
     * 某工单已过账（COMPLETED，且不含当前结转单 docNo）结转单行的累计料/工/费/完工成本（GL 增量锚点）。
     *
     * <p>用于 GL 出凭证按"本期增量"记账（与库存 CostAdjust 增量同口径），防分批跨月重复入账。
     * 排除当前结转单自身（过账时本单已置 COMPLETED，须减去）。无记录各项返回 0。
     */
    PriorCumulative priorCumulativeByWorkOrder(String workOrderDocNo, String excludeSettlementDocNo);

    /**
     * 工单累计成本快照（GL 增量计算用，2 位）。
     *
     * @param materialCost  累计料成本
     * @param laborCost     累计人工成本
     * @param overheadCost  累计制造费用
     * @param completedCost 累计完工应负担成本
     */
    record PriorCumulative(BigDecimal materialCost, BigDecimal laborCost,
                           BigDecimal overheadCost, BigDecimal completedCost) {
    }
}
