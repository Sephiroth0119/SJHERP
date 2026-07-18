package com.sjherp.app.memory;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemoryEntryRepository;
import com.sjherp.domain.memory.MemorySourceType;
import com.sjherp.domain.memory.MemoryType;

@ExtendWith(MockitoExtension.class)
class MemoryIndexRetryJobTest {

    private static final Instant NOW = Instant.parse("2026-07-18T05:00:00Z");

    @Mock
    private MemoryEntryRepository repository;
    @Mock
    private MemoryIndexingService indexingService;

    private MemoryIndexRetryJob job;

    @BeforeEach
    void setUp() {
        job = new MemoryIndexRetryJob(repository, indexingService, properties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void eachRun_onlyProcessesRepositoryDueBatch() {
        MemoryEntry a = entry(11, "MEM-202607-0001");
        MemoryEntry b = entry(12, "MEM-202607-0002");
        when(repository.findIndexCandidates(NOW, 50)).thenReturn(List.of(a, b));

        job.retryDueEntries();

        InOrder order = inOrder(indexingService);
        order.verify(indexingService).indexOne(a.getMemoryNo(), "system:memory-indexer");
        order.verify(indexingService).indexOne(b.getMemoryNo(), "system:memory-indexer");
        verifyNoMoreInteractions(indexingService);
    }

    @Test
    void oneFailure_doesNotBlockRemainingEntries() {
        MemoryEntry a = entry(11, "MEM-202607-0001");
        MemoryEntry b = entry(12, "MEM-202607-0002");
        when(repository.findIndexCandidates(NOW, 50)).thenReturn(List.of(a, b));
        doThrow(new IllegalStateException("state write failed"))
                .when(indexingService).indexOne(a.getMemoryNo(), "system:memory-indexer");

        job.retryDueEntries();

        verify(indexingService).indexOne(b.getMemoryNo(), "system:memory-indexer");
    }

    private static MemoryEntry entry(long id, String memoryNo) {
        MemoryEntry entry = MemoryEntry.create(memoryNo, memoryNo, 1,
                MemoryType.BUSINESS_TERM, "口径", "内容",
                MemorySourceType.SYSTEM, "seed", NOW.minusSeconds(60), null,
                "system", NOW.minusSeconds(60));
        entry.assignId(id);
        return entry;
    }

    private static MemoryProperties properties() {
        return new MemoryProperties(true,
                new MemoryProperties.Embedding("ollama", URI.create("http://localhost:11434"),
                        "qwen3-embedding:0.6b", 1024, 60),
                new MemoryProperties.Vector("qdrant", URI.create("http://localhost:6333"),
                        "sjherp-memory-qwen3-0_6b-1024-v1", "COSINE"),
                new MemoryProperties.Indexing(30, 50, 8));
    }
}
