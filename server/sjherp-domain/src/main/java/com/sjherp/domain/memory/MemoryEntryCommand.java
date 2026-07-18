package com.sjherp.domain.memory;

import java.time.Instant;

/** 新建或替换大记忆时的领域命令。 */
public record MemoryEntryCommand(MemoryType memoryType, String title, String content,
        MemorySourceType sourceType, String sourceRef, Instant validFrom, Instant validTo) {
}
