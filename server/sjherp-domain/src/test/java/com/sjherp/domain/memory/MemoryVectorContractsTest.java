package com.sjherp.domain.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MemoryVectorContractsTest {

    @Test
    void 向量维度必须与元素数量一致且数值有限() {
        assertThatThrownBy(() -> new EmbeddingVector("model", 2,
                List.of(1.0f, Float.NaN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingVector("model", 2, List.of(1.0f)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 向量值保存为不可变副本() {
        List<Float> mutable = new ArrayList<>(List.of(0.25f, 0.75f));
        EmbeddingVector vector = new EmbeddingVector("model", 2, mutable);

        mutable.set(0, 9.0f);

        assertThat(vector.values()).containsExactly(0.25f, 0.75f);
        assertThatThrownBy(() -> vector.values().add(1.0f))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void collection规范拒绝非正维度和未知距离() {
        assertThatThrownBy(() -> new VectorCollectionSpec("memory-v1", 0, "COSINE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VectorCollectionSpec("memory-v1", 1024, "L2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 查询限制必须在一到二百之间() {
        List<Float> vector = List.of(0.1f, 0.2f);

        assertThatThrownBy(() -> new VectorQuery(vector, 0L, Set.of(), 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VectorQuery(vector, 0L, Set.of(), 201, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 向量点拒绝非有限数值() {
        assertThatThrownBy(() -> new VectorPoint(1L, 0L, MemoryType.BUSINESS_TERM,
                MemoryStatus.ACTIVE, MemorySourceType.USER_INPUT,
                List.of(0.1f, Float.POSITIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
