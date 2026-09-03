package com.sjherp.app.consistency;

/** 管理端查询的一致性运行报告不存在。 */
public class ConsistencyReportNotFoundException extends RuntimeException {

    public ConsistencyReportNotFoundException(String runNo) {
        super("一致性检查报告不存在: " + runNo);
    }
}
