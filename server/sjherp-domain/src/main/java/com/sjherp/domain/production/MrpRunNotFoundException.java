package com.sjherp.domain.production;

/**
 * MRP 运行结果不存在异常（M5-T02）→ HTTP 404。
 */
public class MrpRunNotFoundException extends RuntimeException {

    public MrpRunNotFoundException(String docNo) {
        super("MRP 运行结果不存在: " + docNo);
    }

    public static MrpRunNotFoundException byDocNo(String docNo) {
        return new MrpRunNotFoundException(docNo);
    }
}
