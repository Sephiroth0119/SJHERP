package com.sjherp.app.gap;

import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.gap.GapIssueCandidate;
import com.sjherp.domain.gap.GapIssueCandidateRepository;
import com.sjherp.domain.gap.GapIssueClusterWriter;
import com.sjherp.domain.gap.GapIssueStatus;
import com.sjherp.domain.gap.GapRecord;
import com.sjherp.domain.gap.GapRecordNotFoundException;
import com.sjherp.domain.gap.GapRecordRepository;
import com.sjherp.domain.gap.GapRecordService;
import com.sjherp.domain.gap.GapStatus;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionalGapIssueClusterWriter implements GapIssueClusterWriter {
    private final GapIssueCandidateRepository candidates;
    private final GapRecordRepository gaps;
    private final GapRecordService gapService;

    public TransactionalGapIssueClusterWriter(
            GapIssueCandidateRepository candidates,
            GapRecordRepository gaps,
            GapRecordService gapService) {
        this.candidates = candidates;
        this.gaps = gaps;
        this.gapService = gapService;
    }

    @Override
    @Transactional
    @Audited(action = "gap.issue.cluster.write", targetType = "gap_issue")
    public GapIssueCandidate write(GapIssueCandidate candidate, List<String> sources, String operator) {
        GapIssueCandidate saved = candidates.upsert(candidate);
        candidates.addSources(saved.id(), sources);

        if (saved.status() == GapIssueStatus.SENT) {
            triageLateSources(sources, operator);
        }
        return candidates.findById(saved.id()).orElse(saved);
    }

    private void triageLateSources(List<String> sources, String operator) {
        for (String gapNo : sources) {
            GapRecord gap = gaps.findByGapNo(gapNo)
                    .orElseThrow(() -> new GapRecordNotFoundException(gapNo));
            if (gap.getStatus() == GapStatus.NEW) {
                gapService.transitionStatusByGapNo(gapNo, GapStatus.TRIAGED, operator);
            }
        }
    }
}
