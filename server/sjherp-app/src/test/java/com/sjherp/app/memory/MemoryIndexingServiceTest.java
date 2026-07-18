package com.sjherp.app.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sjherp.domain.memory.EmbeddingClient;
import com.sjherp.domain.memory.EmbeddingPurpose;
import com.sjherp.domain.memory.EmbeddingVector;
import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemoryEntryRepository;
import com.sjherp.domain.memory.MemorySourceType;
import com.sjherp.domain.memory.MemoryType;
import com.sjherp.domain.memory.VectorIndex;
import com.sjherp.domain.memory.VectorPoint;
import com.sjherp.infra.memory.QdrantVectorException;

@ExtendWith(MockitoExtension.class)
class MemoryIndexingServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T04:00:00Z");
    private static final String COLLECTION = "sjherp-memory-qwen3-0_6b-1024-v1";
    private static final String MODEL = "qwen3-embedding:0.6b";
    private static final String ORIGINAL_CONTENT = "年采购金额超过50万元";

    @Mock
    private MemoryEntryRepository repository;
    @Mock
    private EmbeddingClient embedding;
    @Mock
    private VectorIndex vectorIndex;
    @Mock
    private MemoryIndexStateService state;

    private MemoryEntry entry;
    private MemoryIndexingService indexing;

    @BeforeEach
    void setUp() {
        entry = entry();
        indexing = new MemoryIndexingService(repository, embedding, vectorIndex, state,
                properties(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 成功索引只写白名单payload并标记成功() {
        when(repository.findByMemoryNo(entry.getMemoryNo())).thenReturn(Optional.of(entry));
        when(embedding.embed(entry.getContent(), EmbeddingPurpose.DOCUMENT)).thenReturn(vector1024());

        assertThat(indexing.indexOne(entry.getMemoryNo(), "system:memory-indexer")).isTrue();

        ArgumentCaptor<VectorPoint> point = ArgumentCaptor.forClass(VectorPoint.class);
        verify(vectorIndex).upsert(point.capture());
        assertThat(point.getValue().memoryEntryId()).isEqualTo(entry.getId());
        assertThat(point.getValue().vector()).hasSize(1024);
        verify(state).markIndexed(entry.getMemoryNo(), COLLECTION, MODEL, 1024,
                "system:memory-indexer");
    }

    @Test
    void qdrant失败不修改原文并记录下次重试() {
        when(repository.findByMemoryNo(entry.getMemoryNo())).thenReturn(Optional.of(entry));
        when(embedding.embed(entry.getContent(), EmbeddingPurpose.DOCUMENT)).thenReturn(vector1024());
        doThrow(new QdrantVectorException("unavailable")).when(vectorIndex).upsert(any());

        assertThat(indexing.indexOne(entry.getMemoryNo(), "system:memory-indexer")).isFalse();

        verify(state).markFailed(entry.getMemoryNo(), "Qdrant 暂不可用",
                NOW.plusSeconds(30), "system:memory-indexer");
        assertThat(entry.getContent()).isEqualTo(ORIGINAL_CONTENT);
    }

    @Test
    void 达到最大重试次数后不再安排自动重试() {
        for (int i = 0; i < 7; i++) {
            entry.markIndexFailed("失败", NOW, "system:memory-indexer", NOW);
        }
        when(repository.findByMemoryNo(entry.getMemoryNo())).thenReturn(Optional.of(entry));
        when(embedding.embed(entry.getContent(), EmbeddingPurpose.DOCUMENT)).thenReturn(vector1024());
        doThrow(new QdrantVectorException("unavailable")).when(vectorIndex).upsert(any());

        assertThat(indexing.indexOne(entry.getMemoryNo(), "system:memory-indexer")).isFalse();

        verify(state).markFailed(entry.getMemoryNo(), "Qdrant 暂不可用",
                null, "system:memory-indexer");
    }

    @Test
    void 删除操作只委托派生向量索引() {
        indexing.deletePoint(17L);

        verify(vectorIndex).delete(17L);
    }

    @Test
    void 提交后监听器吞掉派生索引异常不影响已提交事务() {
        MemoryIndexingService failing = org.mockito.Mockito.mock(MemoryIndexingService.class);
        doThrow(new QdrantVectorException("down")).when(failing).deletePoint(17L);
        MemoryIndexEventListener listener = new MemoryIndexEventListener(failing);

        assertThatCode(() -> listener.on(new MemoryIndexRequestedEvent(
                MemoryIndexOperation.DELETE, "MEM-1", 17L)))
                .doesNotThrowAnyException();
    }

    private static MemoryEntry entry() {
        MemoryEntry result = MemoryEntry.create("MEM-202607-0001", "MEM-202607-0001", 1,
                MemoryType.BUSINESS_TERM, "大客户口径", ORIGINAL_CONTENT,
                MemorySourceType.USER_INPUT, "session-1", NOW.minusSeconds(60),
                null, "user:1", NOW.minusSeconds(60));
        result.assignId(17L);
        return result;
    }

    private static EmbeddingVector vector1024() {
        return new EmbeddingVector(MODEL, 1024, Collections.nCopies(1024, 0.1f));
    }

    private static MemoryProperties properties() {
        return new MemoryProperties(true,
                new MemoryProperties.Embedding("ollama", URI.create("http://localhost:11434"),
                        MODEL, 1024, 60),
                new MemoryProperties.Vector("qdrant", URI.create("http://localhost:6333"),
                        COLLECTION, "COSINE"),
                new MemoryProperties.Indexing(30, 50, 8));
    }
}
