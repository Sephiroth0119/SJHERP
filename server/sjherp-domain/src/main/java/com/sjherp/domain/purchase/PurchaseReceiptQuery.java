package com.sjherp.domain.purchase;

import com.sjherp.domain.common.DocumentStatus;

/**
 * 采购入库单分页查询条件（M3-T06）。
 *
 * @param warehouseId     收货仓库 id 过滤（可空）
 * @param purchaseOrderNo 引用采购订单号过滤（可空，精确匹配）
 * @param status          单据状态过滤（可空）
 * @param page            页码（从 1 起）
 * @param size            每页条数
 */
public record PurchaseReceiptQuery(Long warehouseId, String purchaseOrderNo, DocumentStatus status,
                                   int page, int size) {

    public PurchaseReceiptQuery {
        if (page < 1) {
            throw new IllegalArgumentException("页码必须 >= 1: " + page);
        }
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("每页条数必须在 1-200 之间: " + size);
        }
    }
}
