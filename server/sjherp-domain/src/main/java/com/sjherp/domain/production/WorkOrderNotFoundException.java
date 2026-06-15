package com.sjherp.domain.production;

/**
 * 工单不存在时抛出（M5-T03）。映射到 HTTP 404（见 ProductionExceptionHandler）。
 */
public class WorkOrderNotFoundException extends RuntimeException {

    public WorkOrderNotFoundException(String docNo) {
        super("工单不存在: " + docNo);
    }
}
