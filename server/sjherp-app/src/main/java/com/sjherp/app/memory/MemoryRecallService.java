package com.sjherp.app.memory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.sjherp.domain.memory.EmbeddingClient;
import com.sjherp.domain.memory.EmbeddingPurpose;
import com.sjherp.domain.memory.EmbeddingVector;
import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemoryEntryRepository;
import com.sjherp.domain.memory.MemoryType;
import com.sjherp.domain.memory.VectorIndex;
import com.sjherp.domain.memory.VectorMatch;
import com.sjherp.domain.memory.VectorQuery;

/** 先查派生向量索引，再用 MySQL 真源门禁确认召回资格。 */
public class MemoryRecallService {

    private static final long TENANT_ID = 0L;

    private final EmbeddingClient embedding;
    private final VectorIndex vectorIndex;
    private final MemoryEntryRepository repository;
    private final MemoryProperties.Recall properties;
    private final Clock clock;

    public MemoryRecallService(EmbeddingClient embedding, VectorIndex vectorIndex,
            MemoryEntryRepository repository, MemoryProperties.Recall properties,
            Clock clock) {
        this.embedding = Objects.requireNonNull(embedding, "嵌入客户端不能为空");
        this.vectorIndex = Objects.requireNonNull(vectorIndex, "向量索引不能为空");
        this.repository = Objects.requireNonNull(repository, "记忆仓储不能为空");
        this.properties = Objects.requireNonNull(properties, "召回配置不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    public List<MemoryRecallHit> recall(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }
        EmbeddingVector queryEmbedding = embedding.embed(queryText, EmbeddingPurpose.QUERY);
        List<VectorMatch> matches = vectorIndex.search(new VectorQuery(
                queryEmbedding.values(), TENANT_ID, EnumSet.allOf(MemoryType.class),
                properties.candidateLimit(), properties.minScore()));
        if (matches.isEmpty()) {
            return List.of();
        }

        List<Long> ids = List.copyOf(matches.stream()
                .map(VectorMatch::memoryEntryId)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        Instant asOf = Instant.now(clock);
        Map<Long, MemoryEntry> truth = repository.findRecallableByIds(ids, TENANT_ID, asOf)
                .stream().collect(Collectors.toMap(MemoryEntry::getId, Function.identity()));

        List<MemoryRecallHit> result = new ArrayList<>();
        Set<Long> recalledIds = new HashSet<>();
        for (VectorMatch match : matches) {
            MemoryEntry entry = truth.get(match.memoryEntryId());
            if (entry == null || !recalledIds.add(match.memoryEntryId())) {
                continue;
            }
            result.add(MemoryRecallHit.from("M" + (result.size() + 1), match.score(), entry));
            if (result.size() == properties.maxResults()) {
                break;
            }
        }
        return List.copyOf(result);
    }
}
