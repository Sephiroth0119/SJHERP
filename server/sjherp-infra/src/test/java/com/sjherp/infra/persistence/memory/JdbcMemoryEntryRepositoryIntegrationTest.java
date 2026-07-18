package com.sjherp.infra.persistence.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemorySourceType;
import com.sjherp.domain.memory.MemoryType;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

/** 大记忆真源仓储的真实 MySQL 往返与唯一约束测试。 */
class JdbcMemoryEntryRepositoryIntegrationTest extends MySqlContainerTestBase {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

    private final JdbcMemoryEntryRepository repository = new JdbcMemoryEntryRepository(jdbc);

    @Test
    void 保存回读并按索引到期时间分页() {
        MemoryEntry entry = pending("MEM-IT-" + uniqueSuffix(), NOW.minusSeconds(60));
        repository.save(entry);

        MemoryEntry restored = repository.findByMemoryNo(entry.getMemoryNo()).orElseThrow();
        assertThat(restored.getContent()).isEqualTo("年采购金额超过50万元");
        assertThat(restored.getContentHash()).isEqualTo(entry.getContentHash());
        assertThat(repository.findIndexCandidates(NOW, 10))
                .extracting(MemoryEntry::getMemoryNo)
                .contains(entry.getMemoryNo());
    }

    @Test
    void 同一逻辑键版本号不可重复() {
        String memoryKey = "K-" + uniqueSuffix();
        repository.save(version("MEM-IT-A-" + uniqueSuffix(), memoryKey, 1));

        assertThatThrownBy(() -> repository.save(version(
                "MEM-IT-B-" + uniqueSuffix(), memoryKey, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 召回批量回查只返回当前租户已生效且已索引的活动真源() {
        MemoryEntry active = indexed("MEM-IT-ACTIVE-" + uniqueSuffix(),
                NOW.minusSeconds(60), null);
        MemoryEntry expired = indexed("MEM-IT-EXPIRED-" + uniqueSuffix(),
                NOW.minusSeconds(120), null);
        expired.expire("tester", NOW.minusSeconds(1));
        MemoryEntry pending = version("MEM-IT-PENDING-" + uniqueSuffix(),
                "K-PENDING-" + uniqueSuffix(), 1);
        MemoryEntry future = indexed("MEM-IT-FUTURE-" + uniqueSuffix(),
                NOW.plusSeconds(60), null);
        repository.save(active);
        repository.save(expired);
        repository.save(pending);
        repository.save(future);

        List<MemoryEntry> rows = repository.findRecallableByIds(
                List.of(active.getId(), expired.getId(), pending.getId(), future.getId()),
                0L, NOW);

        assertThat(rows).extracting(MemoryEntry::getId).containsExactly(active.getId());
        assertThat(repository.findRecallableByIds(List.of(), 0L, NOW)).isEmpty();
        assertThat(repository.findRecallableByIds(List.of(active.getId()), 1L, NOW)).isEmpty();
    }

    private static MemoryEntry pending(String memoryNo, Instant nextRetryAt) {
        MemoryEntry entry = MemoryEntry.create(memoryNo, memoryNo, 1,
                MemoryType.BUSINESS_TERM, "大客户口径", "年采购金额超过50万元",
                MemorySourceType.USER_INPUT, "session-1", NOW, null, "tester", NOW);
        entry.markIndexFailed("暂时不可用", nextRetryAt, "system:memory-indexer", NOW);
        return entry;
    }

    private static MemoryEntry version(String memoryNo, String memoryKey, int version) {
        return MemoryEntry.create(memoryNo, memoryKey, version,
                MemoryType.BUSINESS_TERM, "口径", "正文",
                MemorySourceType.SYSTEM, "test", NOW, null, "tester", NOW);
    }

    private static MemoryEntry indexed(String memoryNo, Instant validFrom, Instant validTo) {
        MemoryEntry entry = MemoryEntry.create(memoryNo, memoryNo, 1,
                MemoryType.BUSINESS_TERM, "大客户口径", "年采购金额超过50万元",
                MemorySourceType.USER_INPUT, "session-1", validFrom, validTo,
                "tester", NOW.minusSeconds(180));
        entry.markIndexed("memory-test", "embedding-test", 1024,
                "system:memory-indexer", NOW.minusSeconds(90));
        return entry;
    }
}
