package com.sjherp.app.gap;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.sjherp.domain.gap.BusinessModule;
import com.sjherp.domain.gap.GapIssueCandidate;
import com.sjherp.domain.gap.GapIssueService;
import com.sjherp.domain.gap.GapIssueStatus;
import com.sjherp.domain.gap.GapSeverity;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class GapIssueScheduledPublisherTest {

    @Test
    void manualReviewLeavesPendingButPublishesApprovedAndFailedCandidates() {
        GapIssueService service = serviceWith(List.of(
                candidate(1, GapIssueStatus.PENDING, 0),
                candidate(2, GapIssueStatus.APPROVED, 0),
                candidate(3, GapIssueStatus.FAILED, 1)));

        new GapIssueScheduledPublisher(service, true, 3).publish();

        verify(service, never()).approve(1, "system:gap-issue");
        verify(service, never()).deliver(1, "system:gap-issue");
        verify(service).deliver(2, "system:gap-issue");
        verify(service).deliver(3, "system:gap-issue");
        verify(service).reclaimExpiredSending(Duration.ofMinutes(10), "system:gap-issue");
    }

    @Test
    void automaticModeApprovesThenPublishesPendingCandidate() {
        GapIssueService service = serviceWith(List.of(candidate(1, GapIssueStatus.PENDING, 0)));

        new GapIssueScheduledPublisher(service, false, 3).publish();

        verify(service).approve(1, "system:gap-issue");
        verify(service).deliver(1, "system:gap-issue");
    }

    @Test
    void maxAttemptsPreventsFurtherAutomaticCalls() {
        GapIssueService service = serviceWith(List.of(candidate(1, GapIssueStatus.FAILED, 3)));

        new GapIssueScheduledPublisher(service, false, 3).publish();

        verify(service, never()).approve(anyLong(), anyString());
        verify(service, never()).deliver(1, "system:gap-issue");
    }

    private GapIssueService serviceWith(List<GapIssueCandidate> candidates) {
        GapIssueService service = mock(GapIssueService.class);
        when(service.cluster(anyString())).thenReturn(List.of());
        when(service.dispatchable(3, 200)).thenReturn(candidates);
        return service;
    }

    private static GapIssueCandidate candidate(long id, GapIssueStatus status, int attempts) {
        return new GapIssueCandidate(id, "key-" + id, "key-" + id,
                BusinessModule.GENERAL, GapSeverity.LOW, "title", List.of("scenario"),
                "expected", "missing", List.of("GAP-1"), status, null, null,
                null, null, null, attempts, null, null, null);
    }
}
