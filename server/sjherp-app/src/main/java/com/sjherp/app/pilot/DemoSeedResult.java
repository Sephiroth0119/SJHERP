package com.sjherp.app.pilot;

import java.util.List;

import com.sjherp.app.gl.GlDtos.PeriodCloseReadiness;

/**
 * 演示种子装载结果（M8-T07）。关账预检只读，本结果不代表已关账。
 */
public record DemoSeedResult(
        String packId,
        String companyLabel,
        boolean alreadyLoaded,
        String period,
        boolean periodOpen,
        boolean periodCloseable,
        List<String> consistencyErrors,
        String warehouseName,
        String tradeProductName,
        String openingProductName,
        String supplierName,
        String customerName,
        String paymentAccountName,
        String purchaseOrderNo,
        String purchaseReceiptNo,
        String purchaseInvoiceNo,
        String paymentDocNo,
        String salesOrderNo,
        String salesDeliveryNo,
        String salesInvoiceNo,
        String collectionDocNo,
        String sampleGapNo,
        String message) {

    public DemoSeedResult {
        consistencyErrors = consistencyErrors == null ? List.of() : List.copyOf(consistencyErrors);
    }

    /** 事务提交后再填关账预检，避免把 afterCommit 审计尚未落库误判为 ERROR。 */
    public DemoSeedResult withPrecheck(boolean open, PeriodCloseReadiness readiness) {
        boolean closeable = readiness != null && readiness.closeable();
        List<String> errors = readiness == null ? List.of() : readiness.consistencyErrors();
        return new DemoSeedResult(packId, companyLabel, alreadyLoaded, period, open, closeable, errors,
                warehouseName, tradeProductName, openingProductName, supplierName, customerName,
                paymentAccountName, purchaseOrderNo, purchaseReceiptNo, purchaseInvoiceNo, paymentDocNo,
                salesOrderNo, salesDeliveryNo, salesInvoiceNo, collectionDocNo, sampleGapNo, message);
    }
}
