package com.sjherp.app.gap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.sjherp.domain.gap.BusinessModule;
import com.sjherp.domain.gap.GapIssueCandidate;
import com.sjherp.domain.gap.GapIssueCandidateRepository;
import com.sjherp.domain.gap.GapIssueStatus;
import com.sjherp.domain.gap.GapRecord;
import com.sjherp.domain.gap.GapRecordNotFoundException;
import com.sjherp.domain.gap.GapRecordRepository;
import com.sjherp.domain.gap.GapRecordService;
import com.sjherp.domain.gap.GapSeverity;
import com.sjherp.domain.gap.GapStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TransactionalGapIssueClusterWriterTest {

    @Test
    void lateSourceForSentCandidateIsAppendedAndTriaged() {
        GapIssueCandidateRepository candidates = mock(GapIssueCandidateRepository.class);
        GapRecordRepository gaps = mock(GapRecordRepository.class);
        GapRecordService gapService = mock(GapRecordService.class);
        GapIssueCandidate sent = candidate(GapIssueStatus.SENT);
        when(candidates.upsert(sent)).thenReturn(sent);
        when(candidates.findById(sent.id())).thenReturn(Optional.of(sent));
        when(gaps.findByGapNo("GAP-late")).thenReturn(Optional.of(gap("GAP-late")));

        new TransactionalGapIssueClusterWriter(candidates, gaps, gapService)
                .write(sent, List.of("GAP-late"), "reviewer");

        verify(candidates).addSources(sent.id(), List.of("GAP-late"));
        verify(gapService).transitionStatusByGapNo("GAP-late", GapStatus.TRIAGED, "reviewer");
    }

    @Test
    void unresolvedLateSourcePreventsASeparateStatusWrite() {
        GapIssueCandidateRepository candidates = mock(GapIssueCandidateRepository.class);
        GapRecordRepository gaps = mock(GapRecordRepository.class);
        GapRecordService gapService = mock(GapRecordService.class);
        GapIssueCandidate sent = candidate(GapIssueStatus.SENT);
        when(candidates.upsert(sent)).thenReturn(sent);
        when(gaps.findByGapNo("GAP-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new TransactionalGapIssueClusterWriter(candidates, gaps, gapService)
                .write(sent, List.of("GAP-missing"), "reviewer"))
                .isInstanceOf(GapRecordNotFoundException.class);
        verify(gapService, never()).transitionStatusByGapNo("GAP-missing", GapStatus.TRIAGED, "reviewer");
    }

    private static GapIssueCandidate candidate(GapIssueStatus status) {
        return new GapIssueCandidate(6, "key", "key", BusinessModule.GENERAL, GapSeverity.LOW,
                "title", List.of("scenario"), "expected", "missing", List.of(), status,
                6L, "https://example.test/6", null, null, null, 1, Instant.now(), Instant.now(), null);
    }

    private static GapRecord gap(String gapNo) {
        return new GapRecord(gapNo, null, "title", "scenario", "expected", "missing",
                BusinessModule.GENERAL, GapSeverity.LOW, "reporter", "creator");
    }
}
