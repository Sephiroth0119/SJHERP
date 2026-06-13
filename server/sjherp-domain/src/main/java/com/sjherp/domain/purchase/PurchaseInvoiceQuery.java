package com.sjherp.domain.purchase;

import com.sjherp.domain.common.DocumentStatus;

/**
 * 采购发票分页查询条件（M3-T07）。
 *
 * @param supplierId        供应商 id 过滤（可空）
 * @param purchaseReceiptNo 引用采购入库单号过滤（可空，精确匹配）
 * @param status            单据状态过滤（可空）
 * @param page              页码（从 1 起）
 * @param size              每页条数
 */
public record PurchaseInvoiceQuery(Long supplierId, String purchaseReceiptNo, DocumentStatus status,
                                   int page, int size) {

    public PurchaseInvoiceQuery {
        if (page < 1) {
            throw new IllegalArgumentException("页码必须 >= 1: " + page);
        }
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("每页条数必须在 1-200 之间: " + size);
        }
    }
}
