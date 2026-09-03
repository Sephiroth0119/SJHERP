package com.sjherp.app.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemoryEntryRepository;
import com.sjherp.domain.memory.MemorySourceType;
import com.sjherp.domain.memory.MemoryType;

@ExtendWith(MockitoExtension.class)
class MemoryGovernanceServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T04:00:00Z");

    @Mock
    private MemoryEntryRepository repository;

    @Test
    void 按类型哈希组成重复组并保持仓储顺序() {
        MemoryEntry newest = entry(2L, MemoryType.BUSINESS_TERM,
                "另一标题", "相同正文");
        MemoryEntry older = entry(1L, MemoryType.BUSINESS_TERM,
                "客户口径", "相同正文");
        when(repository.findDuplicateCandidates(0L, 50))
                .thenReturn(List.of(newest, older));
        when(repository.findConflictCandidates(0L, 50)).thenReturn(List.of());

        MemoryGovernanceService.Candidates result =
                new MemoryGovernanceService(repository).findCandidates(50);

        assertThat(result.duplicateGroups()).singleElement().satisfies(group -> {
            assertThat(group.type()).isEqualTo(MemoryType.BUSINESS_TERM);
            assertThat(group.entries()).containsExactly(newest, older);
        });
        assertThat(result.conflictGroups()).isEmpty();
    }

    @Test
    void 按类型和精确标题组成冲突组() {
        MemoryEntry newest = entry(3L, MemoryType.BUSINESS_TERM,
                "客户口径", "年采购超过80万元");
        MemoryEntry older = entry(1L, MemoryType.BUSINESS_TERM,
                "客户口径", "年采购超过50万元");
        when(repository.findDuplicateCandidates(0L, 20)).thenReturn(List.of());
        when(repository.findConflictCandidates(0L, 20))
                .thenReturn(List.of(newest, older));

        MemoryGovernanceService.Candidates result =
                new MemoryGovernanceService(repository).findCandidates(20);

        assertThat(result.duplicateGroups()).isEmpty();
        assertThat(result.conflictGroups()).singleElement().satisfies(group -> {
            assertThat(group.type()).isEqualTo(MemoryType.BUSINESS_TERM);
            assertThat(group.title()).isEqualTo("客户口径");
            assertThat(group.entries()).containsExactly(newest, older);
        });
    }

    @Test
    void 候选组上限必须在一到一百之间() {
        MemoryGovernanceService service = new MemoryGovernanceService(repository);

        assertThatThrownBy(() -> service.findCandidates(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.findCandidates(101))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    private static MemoryEntry entry(long id, MemoryType type, String title, String content) {
        MemoryEntry entry = MemoryEntry.create("MEM-" + id, "MEM-" + id, 1,
                type, title, content, MemorySourceType.USER_INPUT,
                "session-" + id, NOW, null, "user:1", NOW);
        entry.assignId(id);
        return entry;
    }
}
