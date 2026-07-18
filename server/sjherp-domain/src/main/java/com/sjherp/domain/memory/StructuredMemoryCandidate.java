package com.sjherp.domain.memory;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;

/** T02 的结构化候选；候选本身不等于已写入的 MemoryEntry。 */
public record StructuredMemoryCandidate(
        MemoryType memoryType,
        String title,
        Map<String, String> facts,
        MemoryWriteSource source,
        String sourceRef,
        String sessionId,
        boolean requiresHumanApproval) {

    public StructuredMemoryCandidate {
        Objects.requireNonNull(memoryType, "memoryType");
        Objects.requireNonNull(source, "source");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title 不能为空");
        if (facts == null || facts.isEmpty()) throw new IllegalArgumentException("facts 不能为空");
        if (facts.size() > 50) throw new IllegalArgumentException("facts 不能超过 50 项");
        if (sourceRef == null || sourceRef.isBlank()) throw new IllegalArgumentException("sourceRef 不能为空");
        if (memoryType == MemoryType.GAP_SOLUTION && source != MemoryWriteSource.GAP_RECORD) {
            throw new IllegalArgumentException("缺口解决方案必须追溯到 GapRecord");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        facts.forEach((key, value) -> {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("fact key 不能为空");
            if (value == null || value.isBlank()) throw new IllegalArgumentException("fact value 不能为空");
            if (key.strip().length() > 100) throw new IllegalArgumentException("fact key 不能超过 100 字符");
            if (value.strip().length() > 2000) throw new IllegalArgumentException("fact value 不能超过 2000 字符");
            normalized.put(key.strip(), value.strip());
        });
        facts = Map.copyOf(normalized);
    }
}
