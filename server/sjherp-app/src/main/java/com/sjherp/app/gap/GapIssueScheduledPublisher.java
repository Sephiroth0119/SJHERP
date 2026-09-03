package com.sjherp.app.gap;

import com.sjherp.domain.gap.GapIssueCandidate;
import com.sjherp.domain.gap.GapIssueService;
import com.sjherp.domain.gap.GapIssueStatus;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${sjherp.github.issue.auto-run:false}' == 'true' "
        + "and '${sjherp.github.issue.enabled:false}' == 'true'")
public class GapIssueScheduledPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(GapIssueScheduledPublisher.class);
    private static final String SYSTEM_OPERATOR = "system:gap-issue";

    private final GapIssueService service;
    private final boolean manualApproval;
    private final int maxAttempts;

    public GapIssueScheduledPublisher(
            GapIssueService service,
            @Value("${sjherp.github.issue.manual-approval:true}") boolean manualApproval,
            @Value("${sjherp.github.issue.max-attempts:3}") int maxAttempts) {
        this.service = service;
        this.manualApproval = manualApproval;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${sjherp.github.issue.auto-run-delay-ms:60000}")
    public void publish() {
        int reclaimed = service.reclaimExpiredSending(Duration.ofMinutes(10), SYSTEM_OPERATOR);
        if (reclaimed > 0) {
            LOG.warn("Reclaimed {} expired gap Issue delivery leases", reclaimed);
        }

        service.cluster(SYSTEM_OPERATOR);
        for (GapIssueCandidate candidate : service.dispatchable(maxAttempts, 200)) {
            dispatch(candidate);
        }
    }

    private void dispatch(GapIssueCandidate candidate) {
        if (candidate.attemptCount() >= maxAttempts) {
            LOG.warn("Gap Issue candidate {} reached max attempts", candidate.id());
            return;
        }
        if (manualApproval && candidate.status() == GapIssueStatus.PENDING) {
            return;
        }
        if (!isDispatchable(candidate.status())) {
            return;
        }

        try {
            if (candidate.status() == GapIssueStatus.PENDING) {
                service.approve(candidate.id(), SYSTEM_OPERATOR);
            }
            service.deliver(candidate.id(), SYSTEM_OPERATOR);
            LOG.info("Published gap Issue candidate {}", candidate.id());
        } catch (RuntimeException exception) {
            LOG.warn("Gap Issue publish failed for candidate {}: {}",
                    candidate.id(), exception.getClass().getSimpleName());
        }
    }

    private boolean isDispatchable(GapIssueStatus status) {
        return status == GapIssueStatus.PENDING
                || status == GapIssueStatus.APPROVED
                || status == GapIssueStatus.FAILED;
    }
}
