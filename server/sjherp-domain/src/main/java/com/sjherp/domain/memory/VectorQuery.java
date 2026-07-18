package com.sjherp.domain.memory;

import java.util.List;
import java.util.Set;

/** 向量召回查询；业务真源状态仍须由 MySQL 二次确认。 */
public record VectorQuery(List<Float> vector, long tenantId, Set<MemoryType> memoryTypes,
        int limit, Double minScore) {

    public VectorQuery {
        if (vector == null || vector.isEmpty()) {
            throw new IllegalArgumentException("查询向量不能为空");
        }
        EmbeddingVector.validateFinite(vector);
        vector = List.copyOf(vector);
        if (tenantId < 0) {
            throw new IllegalArgumentException("租户主键不能为负数");
        }
        if (memoryTypes == null) {
            throw new IllegalArgumentException("记忆类型集合不能为空");
        }
        memoryTypes = Set.copyOf(memoryTypes);
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("查询数量必须在 1 到 200 之间");
        }
        if (minScore != null && !Double.isFinite(minScore)) {
            throw new IllegalArgumentException("最小相似度必须为有限数");
        }
    }
}
