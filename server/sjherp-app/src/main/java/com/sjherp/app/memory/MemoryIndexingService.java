package com.sjherp.app.memory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.memory.EmbeddingClient;
import com.sjherp.domain.memory.EmbeddingPurpose;
import com.sjherp.domain.memory.EmbeddingVector;
import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemoryEntryNotFoundException;
import com.sjherp.domain.memory.MemoryEntryRepository;
import com.sjherp.domain.memory.MemoryStatus;
import com.sjherp.domain.memory.VectorCollectionSpec;
import com.sjherp.domain.memory.VectorIndex;
import com.sjherp.domain.memory.VectorPoint;
import com.sjherp.infra.memory.OllamaEmbeddingException;
import com.sjherp.infra.memory.QdrantVectorException;

/**
 * 无数据库长事务的向量索引编排器。
 *
 * <p>先读取 MySQL 快照，再调用 Ollama/Qdrant，最后通过
 * {@link MemoryIndexStateService} 的独立短事务写回状态。派生服务失败不会覆盖或
 * 删除已提交的原文真源。
 */
public class MemoryIndexingService {

    private static final long BASE_RETRY_SECONDS = 30L;
    private static final long MAX_RETRY_SECONDS = 3600L;
    private final MemoryEntryRepository repository;
    private final EmbeddingClient embedding;
    private final VectorIndex vectorIndex;
    private final MemoryIndexStateService state;
    private final MemoryProperties properties;
    private final Clock clock;

    public MemoryIndexingService(MemoryEntryRepository repository,
                                 EmbeddingClient embedding,
                                 VectorIndex vectorIndex,
                                 MemoryIndexStateService state,
                                 MemoryProperties properties) {
        this(repository, embedding, vectorIndex, state, properties, Clock.systemUTC());
    }

    MemoryIndexingService(MemoryEntryRepository repository,
                          EmbeddingClient embedding,
                          VectorIndex vectorIndex,
                          MemoryIndexStateService state,
                          MemoryProperties properties, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "memory repository 不能为空");
        this.embedding = Objects.requireNonNull(embedding, "embedding client 不能为空");
        this.vectorIndex = Objects.requireNonNull(vectorIndex, "vector index 不能为空");
        this.state = Objects.requireNonNull(state, "memory index state service 不能为空");
        this.properties = Objects.requireNonNull(properties, "memory properties 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /** 索引单条活动记忆；失败状态成功落库后返回 false。 */
    public boolean indexOne(String memoryNo, String operator) {
        MemoryEntry entry = require(memoryNo);
        if (entry.getStatus() != MemoryStatus.ACTIVE) {
            return false;
        }
        try {
            EmbeddingVector vector = embedding.embed(entry.getContent(), EmbeddingPurpose.DOCUMENT);
            requireExpectedVector(vector);
            vectorIndex.upsert(new VectorPoint(entry.getId(), entry.getTenantId(),
                    entry.getMemoryType(), entry.getStatus(), entry.getSourceType(), vector.values()));
            state.markIndexed(entry.getMemoryNo(), properties.vector().collection(),
                    vector.model(), vector.dimension(), operator);
            return true;
        } catch (RuntimeException exception) {
            Instant nextRetryAt = nextRetryAt(entry);
            state.markFailed(entry.getMemoryNo(), errorSummary(exception), nextRetryAt, operator);
            return false;
        }
    }

    /** 只删除 Qdrant 派生点，不修改或删除 MySQL 真源。 */
    public void deletePoint(long memoryEntryId) {
        vectorIndex.delete(memoryEntryId);
    }

    @Audited(action = "memory.retry_index", targetType = "memory_index")
    public MemoryEntry retryIndex(String memoryNo, String operator) {
        state.markPending(memoryNo, operator);
        indexOne(memoryNo, operator);
        return require(memoryNo);
    }

    @Audited(action = "memory.rebuild_index", targetType = "memory_index")
    public RebuildResult rebuildIndex(String operator) {
        vectorIndex.ensureCollection(new VectorCollectionSpec(
                properties.vector().collection(), properties.embedding().dimension(),
                properties.vector().distance()));
        long afterId = 0L;
        int succeeded = 0;
        int failed = 0;
        while (true) {
            List<MemoryEntry> batch = repository.findActiveAfterId(
                    afterId, properties.indexing().batchSize());
            if (batch.isEmpty()) {
                break;
            }
            for (MemoryEntry entry : batch) {
                if (entry.getId() == null) {
                    throw new IllegalStateException("持久化大记忆缺少主键: " + entry.getMemoryNo());
                }
                afterId = entry.getId();
                if (indexOne(entry.getMemoryNo(), operator)) {
                    succeeded++;
                } else {
                    failed++;
                }
            }
        }
        return new RebuildResult(succeeded, failed, afterId);
    }

    private MemoryEntry require(String memoryNo) {
        return repository.findByMemoryNo(memoryNo)
                .orElseThrow(() -> new MemoryEntryNotFoundException(memoryNo));
    }

    private void requireExpectedVector(EmbeddingVector vector) {
        if (vector.dimension() != properties.embedding().dimension()) {
            throw new IllegalStateException("嵌入向量维度不一致");
        }
        if (!vector.model().equals(properties.embedding().model())) {
            throw new IllegalStateException("嵌入模型与配置不一致");
        }
    }

    private Instant nextRetryAt(MemoryEntry entry) {
        int nextRetryCount = entry.getRetryCount() + 1;
        if (nextRetryCount >= properties.indexing().maxRetries()) {
            return null;
        }
        int exponent = Math.min(entry.getRetryCount(), 7);
        long seconds = Math.min(BASE_RETRY_SECONDS * (1L << exponent), MAX_RETRY_SECONDS);
        return Instant.now(clock).plusSeconds(seconds);
    }

    private static String errorSummary(RuntimeException exception) {
        if (exception instanceof QdrantVectorException) {
            return "Qdrant 暂不可用";
        }
        if (exception instanceof OllamaEmbeddingException) {
            return "Ollama 暂不可用";
        }
        return "记忆索引数据校验失败";
    }

    public record RebuildResult(int succeeded, int failed, long lastProcessedId) {

        public RebuildResult {
            if (succeeded < 0 || failed < 0 || lastProcessedId < 0) {
                throw new IllegalArgumentException("重建结果计数不能为负数");
            }
        }
    }
}
