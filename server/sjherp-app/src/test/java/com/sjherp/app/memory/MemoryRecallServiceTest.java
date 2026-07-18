package com.sjherp.app.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;

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
import com.sjherp.domain.memory.VectorMatch;
import com.sjherp.domain.memory.VectorQuery;

@ExtendWith(MockitoExtension.class)
class MemoryRecallServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T08:00:00Z");

    @Mock
    private EmbeddingClient embedding;
    @Mock
    private VectorIndex vectorIndex;
    @Mock
    private MemoryEntryRepository repository;

    private MemoryRecallService service;

    @BeforeEach
    void setUp() {
        service = new MemoryRecallService(embedding, vectorIndex, repository,
                new MemoryProperties.Recall(12, 5, 0.45d, 6000),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 按向量顺序去重并经真源门禁后限制召回条数() {
        List<VectorMatch> matches = List.of(
                new VectorMatch(2L, 0.99d),
                new VectorMatch(2L, 0.98d),
                new VectorMatch(99L, 0.97d),
                new VectorMatch(1L, 0.96d),
                new VectorMatch(3L, 0.95d),
                new VectorMatch(4L, 0.94d),
                new VectorMatch(5L, 0.93d),
                new VectorMatch(6L, 0.92d));
        when(embedding.embed("大客户怎么定义", EmbeddingPurpose.QUERY))
                .thenReturn(new EmbeddingVector("test", 1, List.of(0.5f)));
        when(vectorIndex.search(org.mockito.ArgumentMatchers.any())).thenReturn(matches);
        when(repository.findRecallableByIds(anyList(), eq(0L), eq(NOW)))
                .thenReturn(List.of(entry(1L), entry(2L), entry(3L),
                        entry(4L), entry(5L), entry(6L)));

        List<MemoryRecallHit> hits = service.recall("大客户怎么定义");

        ArgumentCaptor<VectorQuery> query = ArgumentCaptor.forClass(VectorQuery.class);
        verify(vectorIndex).search(query.capture());
        assertThat(query.getValue().tenantId()).isZero();
        assertThat(query.getValue().memoryTypes()).isEqualTo(EnumSet.allOf(MemoryType.class));
        assertThat(query.getValue().limit()).isEqualTo(12);
        assertThat(query.getValue().minScore()).isEqualTo(0.45d);
        verify(repository).findRecallableByIds(
                List.of(2L, 99L, 1L, 3L, 4L, 5L, 6L), 0L, NOW);
        assertThat(hits).extracting(MemoryRecallHit::memoryEntryId)
                .containsExactly(2L, 1L, 3L, 4L, 5L);
        assertThat(hits).extracting(MemoryRecallHit::citation)
                .containsExactly("M1", "M2", "M3", "M4", "M5");
    }

    @Test
    void 空白查询不访问嵌入与索引() {
        assertThat(service.recall("  ")).isEmpty();

        verifyNoInteractions(embedding, vectorIndex, repository);
    }

    @Test
    void 向量无命中时不访问MySQL() {
        when(embedding.embed("未知口径", EmbeddingPurpose.QUERY))
                .thenReturn(new EmbeddingVector("test", 1, List.of(0.5f)));
        when(vectorIndex.search(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        assertThat(service.recall("未知口径")).isEmpty();

        verify(repository, never()).findRecallableByIds(anyList(), eq(0L), eq(NOW));
    }

    private static MemoryEntry entry(long id) {
        MemoryEntry entry = MemoryEntry.create("MEM-" + id, "KEY-" + id, 1,
                MemoryType.BUSINESS_TERM, "大客户口径", "年采购金额超过50万元",
                MemorySourceType.USER_INPUT, "session-1", NOW.minusSeconds(60),
                null, "user:1", NOW.minusSeconds(60));
        entry.assignId(id);
        return entry;
    }
}
