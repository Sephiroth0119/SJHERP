package com.sjherp.domain.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class MemoryEntryTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

    @Test
    void 新建记忆初始为活动且待索引() {
        MemoryEntry entry = fixture();

        assertThat(entry.getStatus()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(entry.getIndexStatus()).isEqualTo(MemoryIndexStatus.PENDING);
        assertThat(entry.getVersion()).isEqualTo(1);
        assertThat(entry.getRetryCount()).isZero();
        assertThat(entry.getContentHash()).hasSize(64);
        assertThat(entry.getContent()).isEqualTo("年采购金额超过50万元");
    }

    @Test
    void 规范化原文后计算稳定哈希() {
        MemoryEntry first = fixture();
        MemoryEntry second = MemoryEntry.create("MEM-202607-0002", "MEM-202607-0002", 1,
                MemoryType.BUSINESS_TERM, "大客户口径", "  年采购金额超过50万元  ",
                MemorySourceType.USER_INPUT, "session-2", NOW, null, "user:1", NOW);

        assertThat(second.getContent()).isEqualTo(first.getContent());
        assertThat(second.getContentHash()).isEqualTo(first.getContentHash());
    }

    @Test
    void 拒绝非法版本和倒置有效期() {
        assertThatThrownBy(() -> MemoryEntry.create("MEM-1", "K-1", 0,
                MemoryType.BUSINESS_TERM, "标题", "正文", MemorySourceType.SYSTEM,
                "test", NOW, null, "tester", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("版本");

        assertThatThrownBy(() -> MemoryEntry.create("MEM-1", "K-1", 1,
                MemoryType.BUSINESS_TERM, "标题", "正文", MemorySourceType.SYSTEM,
                "test", NOW.plusSeconds(10), NOW, "tester", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("有效期");
    }

    @Test
    void 旧版本被替代后不可恢复为待索引() {
        MemoryEntry entry = fixture();

        entry.markSuperseded("user:1", NOW.plusSeconds(1));

        assertThat(entry.getStatus()).isEqualTo(MemoryStatus.SUPERSEDED);
        assertThat(entry.getValidTo()).isEqualTo(NOW.plusSeconds(1));
        assertThatThrownBy(() -> entry.markPending("user:1", NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 逻辑失效为终态且不允许重复失效() {
        MemoryEntry entry = fixture();

        entry.expire("user:1", NOW.plusSeconds(1));

        assertThat(entry.getStatus()).isEqualTo(MemoryStatus.EXPIRED);
        assertThatThrownBy(() -> entry.expire("user:1", NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 索引失败累加次数且人工重试清空失败信息() {
        MemoryEntry entry = fixture();
        Instant retryAt = NOW.plusSeconds(30);

        entry.markIndexFailed("Qdrant 暂不可用", retryAt, "system:memory-indexer", NOW.plusSeconds(1));
        assertThat(entry.getIndexStatus()).isEqualTo(MemoryIndexStatus.FAILED);
        assertThat(entry.getRetryCount()).isEqualTo(1);
        assertThat(entry.getNextRetryAt()).isEqualTo(retryAt);
        assertThat(entry.getLastIndexError()).isEqualTo("Qdrant 暂不可用");

        entry.markPending("user:1", NOW.plusSeconds(2));
        assertThat(entry.getIndexStatus()).isEqualTo(MemoryIndexStatus.PENDING);
        assertThat(entry.getRetryCount()).isZero();
        assertThat(entry.getNextRetryAt()).isNull();
        assertThat(entry.getLastIndexError()).isNull();
    }

    @Test
    void 索引成功必须记录模型维度与集合() {
        MemoryEntry entry = fixture();

        entry.markIndexed("sjherp-memory-qwen3-0_6b-1024-v1",
                "qwen3-embedding:0.6b", 1024, "system:memory-indexer", NOW.plusSeconds(1));

        assertThat(entry.getIndexStatus()).isEqualTo(MemoryIndexStatus.INDEXED);
        assertThat(entry.getIndexedCollection()).isEqualTo("sjherp-memory-qwen3-0_6b-1024-v1");
        assertThat(entry.getEmbeddingModel()).isEqualTo("qwen3-embedding:0.6b");
        assertThat(entry.getEmbeddingDimension()).isEqualTo(1024);
        assertThat(entry.getRetryCount()).isZero();
    }

    @Test
    void 索引成功拒绝非正维度() {
        MemoryEntry entry = fixture();

        assertThatThrownBy(() -> entry.markIndexed("memory-v1", "model", 0,
                "system:memory-indexer", NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("维度");
    }

    @Test
    void 状态流转参数非法时聚合保持活动() {
        MemoryEntry entry = fixture();

        assertThatThrownBy(() -> entry.markSuperseded(" ", NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(entry.getStatus()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(entry.getValidTo()).isNull();
    }

    @Test
    void 索引成功参数非法时聚合保持待索引() {
        MemoryEntry entry = fixture();

        assertThatThrownBy(() -> entry.markIndexed(" ", "model", 1024,
                "system:memory-indexer", NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(entry.getIndexStatus()).isEqualTo(MemoryIndexStatus.PENDING);
        assertThat(entry.getIndexedCollection()).isNull();
    }

    @Test
    void 替代版本共享逻辑键并指向前版主键() {
        MemoryEntry previous = fixture();
        previous.assignId(7L);

        MemoryEntry replacement = MemoryEntry.createReplacement(
                "MEM-202607-0002", previous,
                MemoryType.METRIC_DEFINITION, "新口径", "新的统计原文",
                MemorySourceType.USER_INPUT, "session-2",
                NOW.plusSeconds(1), null, "user:1", NOW.plusSeconds(1));

        assertThat(replacement.getMemoryKey()).isEqualTo(previous.getMemoryKey());
        assertThat(replacement.getVersion()).isEqualTo(2);
        assertThat(replacement.getPreviousId()).isEqualTo(7L);
        assertThat(replacement.getStatus()).isEqualTo(MemoryStatus.ACTIVE);
    }

    private static MemoryEntry fixture() {
        return MemoryEntry.create("MEM-202607-0001", "MEM-202607-0001", 1,
                MemoryType.BUSINESS_TERM, "大客户口径", "年采购金额超过50万元",
                MemorySourceType.USER_INPUT, "session-1", NOW, null, "user:1", NOW);
    }
}
