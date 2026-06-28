package com.sjherp.domain.production;

/**
 * 退料单不存在时抛出（M5-T04）。映射到 HTTP 404（见 ProductionExceptionHandler）。
 */
public class MaterialReturnNotFoundException extends RuntimeException {

    public MaterialReturnNotFoundException(String docNo) {
        super("退料单不存在: " + docNo);
    }
}
