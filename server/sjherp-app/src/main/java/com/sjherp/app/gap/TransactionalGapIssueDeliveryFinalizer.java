package com.sjherp.app.gap;

import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.gap.GapIssueCandidate;
import com.sjherp.domain.gap.GapIssueCandidateRepository;
import com.sjherp.domain.gap.GapIssueDeliveryFinalizer;
import com.sjherp.domain.gap.GapIssueNotFoundException;
import com.sjherp.domain.gap.GapIssueStateException;
import com.sjherp.domain.gap.GapRecord;
import com.sjherp.domain.gap.GapRecordNotFoundException;
import com.sjherp.domain.gap.GapRecordRepository;
import com.sjherp.domain.gap.GapRecordService;
import com.sjherp.domain.gap.GapStatus;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionalGapIssueDeliveryFinalizer implements GapIssueDeliveryFinalizer {
    private final GapRecordRepository gaps;
    private final GapRecordService gapService;
    private final GapIssueCandidateRepository candidates;

    public TransactionalGapIssueDeliveryFinalizer(
            GapRecordRepository gaps,
            GapRecordService gapService,
            GapIssueCandidateRepository candidates) {
        this.gaps = gaps;
        this.gapService = gapService;
        this.candidates = candidates;
    }

    @Override
    @Transactional
    @Audited(action = "gap.issue.claim", targetType = "gap_issue")
    public Optional<String> claimDelivery(long candidateId, String operator) {
        return candidates.claimForSend(candidateId);
    }

    @Override
    @Transactional
    @Audited(action = "gap.issue.finalize", targetType = "gap_issue")
    public void finalizeDelivery(
            GapIssueCandidate candidate,
            String leaseToken,
            long number,
            String url,
            String operator) {
        GapIssueCandidate current = candidates.findById(candidate.id())
                .orElseThrow(() -> new GapIssueNotFoundException(candidate.id()));
        for (String gapNo : current.sourceGapNos()) {
            GapRecord gap = gaps.findByGapNo(gapNo)
                    .orElseThrow(() -> new GapRecordNotFoundException(gapNo));
            if (gap.getStatus() == GapStatus.NEW) {
                gapService.transitionStatusByGapNo(gapNo, GapStatus.TRIAGED, operator);
            }
        }

        try {
            candidates.markSent(candidate.id(), leaseToken, number, url);
        } catch (IllegalStateException exception) {
            throw new GapIssueStateException("delivery finalization conflict");
        }
    }

    @Override
    @Transactional
    @Audited(action = "gap.issue.fail", targetType = "gap_issue")
    public void failDelivery(long candidateId, String leaseToken, String failureType, String operator) {
        try {
            candidates.markFailed(candidateId, leaseToken, failureType);
        } catch (IllegalStateException exception) {
            throw new GapIssueStateException("delivery failure recording conflict");
        }
    }
}
