package com.sjherp.app.gap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
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

class TransactionalGapIssueDeliveryFinalizerTest {

    @Test
    void finalizerReloadsCurrentSourcesSoGapAddedDuringSendingIsTriaged() {
        GapRecordRepository gaps = mock(GapRecordRepository.class);
        GapRecordService gapService = mock(GapRecordService.class);
        GapIssueCandidateRepository candidates = mock(GapIssueCandidateRepository.class);
        GapIssueCandidate stale = candidate(List.of("GAP-1"));
        GapIssueCandidate current = candidate(List.of("GAP-1", "GAP-2"));
        when(candidates.findById(7)).thenReturn(Optional.of(current));
        when(gaps.findByGapNo("GAP-1")).thenReturn(Optional.of(gap("GAP-1")));
        when(gaps.findByGapNo("GAP-2")).thenReturn(Optional.of(gap("GAP-2")));

        new TransactionalGapIssueDeliveryFinalizer(gaps, gapService, candidates)
                .finalizeDelivery(stale, "lease", 77, "https://example.test/77", "reviewer");

        verify(gapService).transitionStatusByGapNo("GAP-1", GapStatus.TRIAGED, "reviewer");
        verify(gapService).transitionStatusByGapNo("GAP-2", GapStatus.TRIAGED, "reviewer");
        verify(candidates).markSent(7, "lease", 77, "https://example.test/77");
    }

    @Test
    void finalizerDoesNotMarkCandidateSentWhenAnyCurrentSourceCannotBeResolved() {
        GapRecordRepository gaps = mock(GapRecordRepository.class);
        GapRecordService gapService = mock(GapRecordService.class);
        GapIssueCandidateRepository candidates = mock(GapIssueCandidateRepository.class);
        GapIssueCandidate current = candidate(List.of("GAP-1", "GAP-missing"));
        when(candidates.findById(7)).thenReturn(Optional.of(current));
        when(gaps.findByGapNo("GAP-1")).thenReturn(Optional.of(gap("GAP-1")));
        when(gaps.findByGapNo("GAP-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new TransactionalGapIssueDeliveryFinalizer(gaps, gapService, candidates)
                .finalizeDelivery(current, "lease", 77, "https://example.test/77", "reviewer"))
                .isInstanceOf(GapRecordNotFoundException.class);
        verify(candidates, never()).markSent(eq(7L), eq("lease"), eq(77L), eq("https://example.test/77"));
    }

    private static GapIssueCandidate candidate(List<String> sources) {
        return new GapIssueCandidate(7, "key", "key", BusinessModule.GENERAL, GapSeverity.LOW,
                "title", List.of("scenario"), "expected", "missing", sources, GapIssueStatus.SENDING,
                null, null, null, null, null, 1, Instant.now(), Instant.now(), Instant.now());
    }

    private static GapRecord gap(String gapNo) {
        return new GapRecord(gapNo, null, "title", "scenario", "expected", "missing",
                BusinessModule.GENERAL, GapSeverity.LOW, "reporter", "creator");
    }
}
