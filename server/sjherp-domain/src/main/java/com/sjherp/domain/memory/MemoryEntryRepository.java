package com.sjherp.domain.memory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/** 大记忆 MySQL 真源仓储端口，不提供物理删除。 */
public interface MemoryEntryRepository {

    void save(MemoryEntry entry);

    Optional<MemoryEntry> findByMemoryNo(String memoryNo);

    Optional<MemoryEntry> findActiveByMemoryKey(String memoryKey);

    PageResult<MemoryEntry> search(MemoryEntryQuery query);

    List<MemoryEntry> findIndexCandidates(Instant dueAt, int limit);

    List<MemoryEntry> findActiveAfterId(long afterId, int limit);

    List<MemoryEntry> findRecallableByIds(List<Long> ids, long tenantId, Instant asOf);

    List<MemoryEntry> findDuplicateCandidates(long tenantId, int groupLimit);

    List<MemoryEntry> findConflictCandidates(long tenantId, int groupLimit);

    List<MemoryEntry> findByMemoryNosForUpdate(List<String> memoryNos);
}
