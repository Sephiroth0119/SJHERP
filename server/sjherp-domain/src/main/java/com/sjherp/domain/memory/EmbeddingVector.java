package com.sjherp.domain.memory;

import java.util.List;

/** 嵌入模型返回的不可变向量。 */
public record EmbeddingVector(String model, int dimension, List<Float> values) {

    public EmbeddingVector {
        model = requireText(model, "嵌入模型");
        if (dimension < 1) {
            throw new IllegalArgumentException("向量维度必须为正数");
        }
        if (values == null || values.size() != dimension) {
            throw new IllegalArgumentException("向量元素数量必须与维度一致");
        }
        validateFinite(values);
        values = List.copyOf(values);
    }

    static void validateFinite(List<Float> values) {
        for (Float value : values) {
            if (value == null || !Float.isFinite(value)) {
                throw new IllegalArgumentException("向量值必须为有限数");
            }
        }
    }

    static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        return value.strip();
    }
}
