package com.sjherp.domain.production;

/**
 * 领料单不存在时抛出（M5-T04）。映射到 HTTP 404（见 ProductionExceptionHandler）。
 */
public class MaterialIssueNotFoundException extends RuntimeException {

    public MaterialIssueNotFoundException(String docNo) {
        super("领料单不存在: " + docNo);
    }
}
