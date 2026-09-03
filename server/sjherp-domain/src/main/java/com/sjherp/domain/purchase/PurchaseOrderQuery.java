package com.sjherp.domain.purchase;

import com.sjherp.domain.common.DocumentStatus;

/**
 * 采购订单分页查询条件（M3-T05）。
 *
 * @param supplierId    供应商 id 过滤（可空）
 * @param status        单据状态过滤（可空）
 * @param receivableOnly 是否仅查询仍存在未收数量的订单
 * @param page          页码（从 1 起）
 * @param size          每页条数
 */
public record PurchaseOrderQuery(
        Long supplierId, DocumentStatus status, boolean receivableOnly, int page, int size) {

    /** 保留既有订单查询调用的兼容构造器；普通订单列表不启用可收过滤。 */
    public PurchaseOrderQuery(Long supplierId, DocumentStatus status, int page, int size) {
        this(supplierId, status, false, page, size);
    }

    public PurchaseOrderQuery {
        if (page < 1) {
            throw new IllegalArgumentException("页码必须 >= 1: " + page);
        }
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("每页条数必须在 1-200 之间: " + size);
        }
    }
}
