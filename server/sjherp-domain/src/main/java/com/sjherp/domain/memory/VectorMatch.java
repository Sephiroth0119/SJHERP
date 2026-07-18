package com.sjherp.domain.memory;

/** 向量召回命中，仅返回真源主键和相似度。 */
public record VectorMatch(long memoryEntryId, double score) {

    public VectorMatch {
        if (memoryEntryId < 1) {
            throw new IllegalArgumentException("大记忆主键必须为正数");
        }
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("相似度必须为有限数");
        }
    }
}
