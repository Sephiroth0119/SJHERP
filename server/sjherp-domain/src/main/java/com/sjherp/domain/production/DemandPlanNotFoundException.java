package com.sjherp.domain.production;

/**
 * 需求计划不存在异常（M5-T02）→ HTTP 404。
 */
public class DemandPlanNotFoundException extends RuntimeException {

    public DemandPlanNotFoundException(String docNo) {
        super("需求计划不存在: " + docNo);
    }

    public static DemandPlanNotFoundException byDocNo(String docNo) {
        return new DemandPlanNotFoundException(docNo);
    }
}
