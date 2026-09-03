package com.sjherp.domain.memory;

import java.util.List;
import java.util.Objects;

/** 写入派生向量库的最小化点数据，不携带标题或原文。 */
public record VectorPoint(long memoryEntryId, long tenantId, MemoryType memoryType,
        MemoryStatus memoryStatus, MemorySourceType sourceType, List<Float> vector) {

    public VectorPoint {
        if (memoryEntryId < 1) {
            throw new IllegalArgumentException("大记忆主键必须为正数");
        }
        if (tenantId < 0) {
            throw new IllegalArgumentException("租户主键不能为负数");
        }
        Objects.requireNonNull(memoryType, "记忆类型不能为空");
        Objects.requireNonNull(memoryStatus, "记忆状态不能为空");
        Objects.requireNonNull(sourceType, "来源类型不能为空");
        if (vector == null || vector.isEmpty()) {
            throw new IllegalArgumentException("向量不能为空");
        }
        EmbeddingVector.validateFinite(vector);
        vector = List.copyOf(vector);
    }
}
