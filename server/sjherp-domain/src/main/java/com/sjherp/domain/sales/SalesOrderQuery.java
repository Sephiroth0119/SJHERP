package com.sjherp.domain.sales;

import com.sjherp.domain.common.DocumentStatus;

/**
 * 销售订单分页查询条件（M3-T08）。
 *
 * @param customerId 客户 id 过滤（可空）
 * @param status     单据状态过滤（可空）
 * @param deliverableOnly 是否只查 APPROVED/EXECUTING 且至少一行仍有剩余可发量的订单
 * @param page       页码（从 1 起）
 * @param size       每页条数
 */
public record SalesOrderQuery(Long customerId, DocumentStatus status, boolean deliverableOnly,
                              int page, int size) {

    public SalesOrderQuery {
        if (page < 1) {
            throw new IllegalArgumentException("页码必须 >= 1: " + page);
        }
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("每页条数必须在 1-200 之间: " + size);
        }
    }

    /** 保留既有调用兼容：普通订单查询不启用可发货候选过滤。 */
    public SalesOrderQuery(Long customerId, DocumentStatus status, int page, int size) {
        this(customerId, status, false, page, size);
    }
}
