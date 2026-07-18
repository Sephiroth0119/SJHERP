package com.sjherp.app.memory;

import java.util.Objects;

/** MySQL 真源事务内发布、提交后消费的索引请求。 */
public record MemoryIndexRequestedEvent(MemoryIndexOperation operation,
        String memoryNo, long memoryEntryId) {

    public MemoryIndexRequestedEvent {
        Objects.requireNonNull(operation, "索引操作不能为空");
        if (memoryNo == null || memoryNo.isBlank()) {
            throw new IllegalArgumentException("记忆编号不能为空");
        }
        memoryNo = memoryNo.strip();
        if (memoryEntryId < 1) {
            throw new IllegalArgumentException("大记忆主键必须为正数");
        }
    }
}
