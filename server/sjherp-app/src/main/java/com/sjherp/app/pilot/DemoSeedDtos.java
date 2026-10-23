package com.sjherp.app.pilot;

import java.util.List;

/**
 * 演示种子 REST DTO（M8-T07）。
 */
public final class DemoSeedDtos {

    private DemoSeedDtos() {
    }

    /** 装载请求：pack 可空，默认 sunshine */
    public record SeedRequest(String pack) {
    }

    public record SeedResponse(
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

        public static SeedResponse from(DemoSeedResult result) {
            return new SeedResponse(
                    result.packId(), result.companyLabel(), result.alreadyLoaded(), result.period(),
                    result.periodOpen(), result.periodCloseable(), result.consistencyErrors(),
                    result.warehouseName(), result.tradeProductName(), result.openingProductName(),
                    result.supplierName(), result.customerName(), result.paymentAccountName(),
                    result.purchaseOrderNo(), result.purchaseReceiptNo(), result.purchaseInvoiceNo(),
                    result.paymentDocNo(), result.salesOrderNo(), result.salesDeliveryNo(),
                    result.salesInvoiceNo(), result.collectionDocNo(), result.sampleGapNo(),
                    result.message());
        }
    }

    public record PackInfo(String id, String companyLabel, String warehouseName) {
        public static PackInfo from(DemoCompanyPack pack) {
            return new PackInfo(pack.id(), pack.companyLabel(), pack.warehouseName());
        }
    }
}
