package com.sjherp.domain.memory;

/** 可写入大记忆的业务来源，来源编号必须能回查原始记录。 */
public enum MemoryWriteSource {
    GAP_RECORD,
    AGENT_SESSION,
    USER_INPUT,
    BUSINESS_DOCUMENT
}
