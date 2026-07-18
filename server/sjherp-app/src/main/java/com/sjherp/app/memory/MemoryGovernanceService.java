package com.sjherp.app.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemoryEntryRepository;
import com.sjherp.domain.memory.MemoryType;

/** 基于 MySQL 真源的确定性治理候选查询，不访问向量库。 */
public class MemoryGovernanceService {

    private static final long TENANT_ID = 0L;

    private final MemoryEntryRepository repository;

    public MemoryGovernanceService(MemoryEntryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "memory repository 不能为空");
    }

    @Transactional(readOnly = true)
    public Candidates findCandidates(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("候选组数量必须在 1 到 100 之间");
        }
        return new Candidates(
                duplicateGroups(repository.findDuplicateCandidates(TENANT_ID, limit)),
                conflictGroups(repository.findConflictCandidates(TENANT_ID, limit)));
    }

    private static List<DuplicateGroup> duplicateGroups(List<MemoryEntry> entries) {
        Map<DuplicateKey, List<MemoryEntry>> grouped = new LinkedHashMap<>();
        for (MemoryEntry entry : entries) {
            DuplicateKey key = new DuplicateKey(entry.getMemoryType(), entry.getContentHash());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }
        return grouped.entrySet().stream()
                .map(item -> new DuplicateGroup(item.getKey().type(), item.getValue()))
                .toList();
    }

    private static List<ConflictGroup> conflictGroups(List<MemoryEntry> entries) {
        Map<ConflictKey, List<MemoryEntry>> grouped = new LinkedHashMap<>();
        for (MemoryEntry entry : entries) {
            ConflictKey key = new ConflictKey(entry.getMemoryType(), entry.getTitle());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }
        return grouped.entrySet().stream()
                .map(item -> new ConflictGroup(item.getKey().type(),
                        item.getKey().title(), item.getValue()))
                .toList();
    }

    private record DuplicateKey(MemoryType type, String contentHash) {
    }

    private record ConflictKey(MemoryType type, String title) {
    }

    public record Candidates(List<DuplicateGroup> duplicateGroups,
                             List<ConflictGroup> conflictGroups) {
        public Candidates {
            duplicateGroups = List.copyOf(duplicateGroups);
            conflictGroups = List.copyOf(conflictGroups);
        }
    }

    public record DuplicateGroup(MemoryType type, List<MemoryEntry> entries) {
        public DuplicateGroup {
            Objects.requireNonNull(type, "重复候选类型不能为空");
            entries = List.copyOf(entries);
        }
    }

    public record ConflictGroup(MemoryType type, String title, List<MemoryEntry> entries) {
        public ConflictGroup {
            Objects.requireNonNull(type, "冲突候选类型不能为空");
            Objects.requireNonNull(title, "冲突候选标题不能为空");
            entries = List.copyOf(entries);
        }
    }
}
