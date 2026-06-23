package com.sjherp.domain.production;

/**
 * 报工单不存在（M5-T05）。REST 层映射为 404。
 */
public class ProductionReportNotFoundException extends RuntimeException {

    public ProductionReportNotFoundException(String docNo) {
        super("报工单不存在: " + docNo);
    }
}
