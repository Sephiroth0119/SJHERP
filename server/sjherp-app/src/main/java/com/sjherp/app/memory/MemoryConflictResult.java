package com.sjherp.app.memory;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.sjherp.domain.common.audit.AuditTarget;
import com.sjherp.domain.memory.MemoryEntry;

/** 一次整组冲突治理的脱敏审计目标。 */
public record MemoryConflictResult(List<MemoryEntry> entries) implements AuditTarget {

    public MemoryConflictResult {
        Objects.requireNonNull(entries, "冲突治理结果不能为空");
        entries = List.copyOf(entries);
        if (entries.size() < 2) {
            throw new IllegalArgumentException("冲突治理结果至少包含两条记忆");
        }
    }

    @Override
    public Long auditTargetId() {
        return null;
    }

    @Override
    public String auditTargetCode() {
        return entries.getFirst().getMemoryNo();
    }

    @Override
    public String auditSummary() {
        return "动作=整组标记冲突, 数量=" + entries.size() + ", 记忆编号="
                + entries.stream().map(MemoryEntry::getMemoryNo).sorted()
                        .collect(Collectors.joining(","));
    }
}
