package com.sjherp.app.gap;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.gap.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class TransactionalGapIssueClusterWriter implements GapIssueClusterWriter {
 private final GapIssueCandidateRepository candidates; private final GapRecordRepository gaps; private final GapRecordService gapService;
 public TransactionalGapIssueClusterWriter(GapIssueCandidateRepository c, GapRecordRepository g, GapRecordService s){candidates=c;gaps=g;gapService=s;}
 @Override @Transactional @Audited(action="gap.issue.cluster.write",targetType="gap_issue")
 public GapIssueCandidate write(GapIssueCandidate candidate,List<String> sources,String operator){
  GapIssueCandidate saved=candidates.upsert(candidate); candidates.addSources(saved.id(),sources);
  if(saved.status()==GapIssueStatus.SENT) for(String gapNo:sources){GapRecord gap=gaps.findByGapNo(gapNo).orElseThrow(()->new GapRecordNotFoundException(gapNo));if(gap.getStatus()==GapStatus.NEW)gapService.transitionStatusByGapNo(gapNo,GapStatus.TRIAGED,operator);}
  return candidates.findById(saved.id()).orElse(saved);
 }
}
