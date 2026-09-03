package com.sjherp.app.memory;

import java.time.Instant;

import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemorySourceType;
import com.sjherp.domain.memory.MemoryType;

/** 经 MySQL 真源确认后可注入聊天提示的只读记忆命中。 */
public record MemoryRecallHit(long memoryEntryId, String citation, double score,
        MemoryType memoryType, String title, String content,
        MemorySourceType sourceType, String sourceRef,
        Instant validFrom, Instant updatedAt) {

    static MemoryRecallHit from(String citation, double score, MemoryEntry entry) {
        return new MemoryRecallHit(entry.getId(), citation, score, entry.getMemoryType(),
                entry.getTitle(), entry.getContent(), entry.getSourceType(),
                entry.getSourceRef(), entry.getValidFrom(), entry.getUpdatedAt());
    }
}
