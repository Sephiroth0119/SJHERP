package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 报工单仓储接口（M5-T05）。实现由 infra 层提供。
 */
public interface ProductionReportRepository {

    /** 保存报工单（新建时插入，已存在时更新） */
    void save(ProductionReport report);

    /** 按单号查询（不存在返回 empty） */
    Optional<ProductionReport> findByDocNo(String docNo);

    /** 分页查询（支持工单号/状态过滤） */
    PageResult<ProductionReport> search(ProductionReportQuery query);

    /**
     * 汇总该工单已过账（COMPLETED）报工单的 inbound_cost 之和。
     *
     * <p>这是"料费已结转"锚点：分批完工时本次应结转料费 = 工单全部已过账领料 issuedCost
     * 之和 − 本值，保证 Σ完工入库金额 ≡ Σ领料出库金额（料的进出守恒，设计真源 R1），
     * 杜绝多次报工重复计入同一批料费（评审 P0）。无记录返回 0（非 null）。
     */
    BigDecimal sumInboundCostByWorkOrder(String workOrderDocNo);
}
