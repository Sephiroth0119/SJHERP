package com.sjherp.domain.gap;

/**
 * 流程缺口记录不存在的领域异常（API 层映射为 404 {"error"}）。
 */
public class GapRecordNotFoundException extends RuntimeException {

    public GapRecordNotFoundException(long id) {
        super("流程缺口记录不存在: id=" + id);
    }
    public GapRecordNotFoundException(String gapNo) {
        super("流程缺口记录不存在: gapNo=" + gapNo);
    }
}
