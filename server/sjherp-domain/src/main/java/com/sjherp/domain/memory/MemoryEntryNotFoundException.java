package com.sjherp.domain.memory;

/** 指定大记忆编号不存在。 */
public final class MemoryEntryNotFoundException extends RuntimeException {

    public MemoryEntryNotFoundException(String memoryNo) {
        super("大记忆不存在: " + memoryNo);
    }
}
