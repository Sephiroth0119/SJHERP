package com.sjherp.app.gap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.gap.*;

@Service
public class TransactionalGapIssueDeliveryFinalizer implements GapIssueDeliveryFinalizer {
    private final GapRecordRepository gaps;
    private final GapRecordService gapService;
    private final GapIssueCandidateRepository candidates;
    public TransactionalGapIssueDeliveryFinalizer(GapRecordRepository gaps, GapRecordService gapService,
                                                   GapIssueCandidateRepository candidates) {
        this.gaps = gaps; this.gapService = gapService; this.candidates = candidates;
    }
    @Override @Transactional
    @Audited(action = "gap.issue.claim", targetType = "gap_issue")
    public java.util.Optional<String> claimDelivery(long candidateId, String operator) { return candidates.claimForSend(candidateId); }
    @Override @Transactional
    @Audited(action = "gap.issue.finalize", targetType = "gap_issue")
    public void finalizeDelivery(GapIssueCandidate candidate, String leaseToken, long number, String url,
                                 String operator) {
        GapIssueCandidate current = candidates.findById(candidate.id())
                .orElseThrow(() -> new GapIssueNotFoundException(candidate.id()));
        for (String gapNo : current.sourceGapNos()) {
            GapRecord gap = gaps.findByGapNo(gapNo).orElseThrow(() -> new GapRecordNotFoundException(gapNo));
            if (gap.getStatus() == GapStatus.NEW) gapService.transitionStatusByGapNo(gapNo, GapStatus.TRIAGED, operator);
        }
        try { candidates.markSent(candidate.id(), leaseToken, number, url); }
        catch (IllegalStateException e) { throw new GapIssueStateException("delivery finalization conflict"); }
    }
    @Override @Transactional
    @Audited(action = "gap.issue.fail", targetType = "gap_issue")
    public void failDelivery(long candidateId, String leaseToken, String failureType, String operator) {
        try { candidates.markFailed(candidateId, leaseToken, failureType); }
        catch (IllegalStateException e) { throw new GapIssueStateException("delivery failure recording conflict"); }
    }
}
