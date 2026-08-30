package com.sjherp.domain.sales;

import com.sjherp.domain.common.DocumentStatus;

/**
 * 销售出库单分页查询条件（M3-T09）。
 *
 * @param salesOrderNo 关联销售订单号过滤（可空）
 * @param warehouseId  出库仓库 id 过滤（可空）
 * @param status       单据状态过滤（可空）
 * @param invoiceableOnly 是否只返回已过账且至少有一行仍可开票的出库单
 * @param page         页码（从 1 起）
 * @param size         每页条数
 */
public record SalesDeliveryQuery(String salesOrderNo, Long warehouseId, DocumentStatus status,
                                 boolean invoiceableOnly, int page, int size) {

    /** 兼容既有普通分页调用；默认不启用销售发票候选过滤。 */
    public SalesDeliveryQuery(String salesOrderNo, Long warehouseId, DocumentStatus status,
                              int page, int size) {
        this(salesOrderNo, warehouseId, status, false, page, size);
    }

    public SalesDeliveryQuery {
        if (page < 1) {
            throw new IllegalArgumentException("页码必须 >= 1: " + page);
        }
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("每页条数必须在 1-200 之间: " + size);
        }
    }
}
