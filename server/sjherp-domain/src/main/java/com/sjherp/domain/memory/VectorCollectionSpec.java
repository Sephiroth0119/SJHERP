package com.sjherp.domain.memory;

import java.util.Locale;

/** 向量集合规格；当前只允许设计锁定的余弦距离。 */
public record VectorCollectionSpec(String name, int dimension, String distance) {

    public VectorCollectionSpec {
        name = EmbeddingVector.requireText(name, "向量集合名称");
        if (dimension < 1) {
            throw new IllegalArgumentException("向量维度必须为正数");
        }
        distance = EmbeddingVector.requireText(distance, "距离类型").toUpperCase(Locale.ROOT);
        if (!"COSINE".equals(distance)) {
            throw new IllegalArgumentException("当前只支持 COSINE 距离");
        }
    }
}
