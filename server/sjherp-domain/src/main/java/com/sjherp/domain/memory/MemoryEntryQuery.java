package com.sjherp.domain.memory;

/** 大记忆管理分页查询条件。 */
public record MemoryEntryQuery(MemoryType memoryType, MemoryStatus status,
        MemoryIndexStatus indexStatus, int page, int size) {

    public MemoryEntryQuery {
        if (page < 1) {
            throw new IllegalArgumentException("page 必须 >= 1");
        }
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("size 必须在 1 到 200 之间");
        }
    }
}
