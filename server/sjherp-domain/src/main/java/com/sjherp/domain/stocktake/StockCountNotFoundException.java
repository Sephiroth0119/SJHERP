package com.sjherp.domain.stocktake;

/**
 * 盘点单不存在领域异常（M3-T03）：按单据号查不到时抛出 → REST 404。
 */
public class StockCountNotFoundException extends RuntimeException {

    public StockCountNotFoundException(String docNo) {
        super("盘点单不存在: " + docNo);
    }
}
