package com.sjherp.app.gap;

import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.gap.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeveloperAgentService {
    private final GapIssueCandidateRepository candidates;
    private final DeveloperAgentTaskRepository tasks;
    private final GapRecordRepository gaps;
    private final GapRecordService gapService;
    private final DeveloperAgentRunner runner;
    private final WorkspacePolicy workspacePolicy;
    public DeveloperAgentService(GapIssueCandidateRepository candidates, DeveloperAgentTaskRepository tasks, GapRecordRepository gaps, GapRecordService gapService, DeveloperAgentRunner runner, WorkspacePolicy workspacePolicy) { this.candidates=candidates;this.tasks=tasks;this.gaps=gaps;this.gapService=gapService;this.runner=runner;this.workspacePolicy=workspacePolicy; }

    @Transactional
    @Audited(action="developer.task.create", targetType="developer_task")
    public DeveloperAgentTask start(long candidateId, String operator) {
        GapIssueCandidate c=candidates.findById(candidateId).orElseThrow(()->new GapIssueNotFoundException(candidateId));
        if(c.status()!=GapIssueStatus.SENT || c.issueNumber()==null) throw new GapIssueStateException("only SENT Issue candidates may start development");
        String branch="codex/dev/"+c.idempotencyKey();
        String workspace=workspacePolicy.validate(branch, java.nio.file.Path.of("developer-agent-workspaces", c.idempotencyKey())).toString();
        DeveloperAgentTask task=new DeveloperAgentTask(0,candidateId,"developer:"+c.idempotencyKey(),DeveloperAgentTaskStatus.QUEUED,branch,workspace, runner.kind(),null,0,List.of(),false,false,false,null,false);
        DeveloperAgentTask saved=tasks.createIfAbsent(task,operator);
        for(String gapNo:c.sourceGapNos()){GapRecord gap=gaps.findByGapNo(gapNo).orElseThrow(()->new GapRecordNotFoundException(gapNo)); if(gap.getStatus()==GapStatus.TRIAGED)gapService.transitionStatusByGapNo(gapNo,GapStatus.IN_DEVELOPMENT,operator);}
        return saved;
    }
    public DeveloperAgentTask get(long id){return tasks.findById(id).orElseThrow(()->new IllegalArgumentException("developer task not found"));}
    @Audited(action="developer.task.run",targetType="developer_task")
    public DeveloperAgentTask run(long id,String operator){if(!runner.available())throw new GapIssueDisabledException("developer agent execution is disabled");DeveloperAgentTask queued=get(id);GapIssueCandidate candidate=candidates.findById(queued.candidateId()).orElseThrow(()->new GapIssueNotFoundException(queued.candidateId()));String lease=tasks.claim(id,Instant.now()).orElseThrow(()->new GapIssueStateException("task is already leased or not retryable"));DeveloperAgentTask claimed=get(id);try {DeveloperAgentRunner.Result result=runner.run(new DeveloperAgentRunner.RunRequest(claimed,candidate));if(result.generatedArtifacts().isEmpty()||result.generatedArtifacts().stream().anyMatch(String::isBlank)||!result.targetedTestsGreen()){tasks.markFailed(id,DeveloperAgentTaskStatus.RUNNING,lease,"QUALITY_GATE","missing artifacts or targeted tests");return get(id);}tasks.transition(id,DeveloperAgentTaskStatus.RUNNING,DeveloperAgentTaskStatus.TESTING,lease,result.generatedArtifacts(),true,false,false,null,result.outputSummary());if(!result.fullTestsGreen()||!result.ciGreen()||result.ciEvidence()==null||result.ciEvidence().isBlank()){tasks.markFailed(id,DeveloperAgentTaskStatus.TESTING,lease,"QUALITY_GATE","full tests or CI evidence missing");return get(id);}tasks.transition(id,DeveloperAgentTaskStatus.TESTING,DeveloperAgentTaskStatus.AWAITING_REVIEW,lease,result.generatedArtifacts(),true,true,true,result.ciEvidence(),result.outputSummary());return get(id);} catch(RuntimeException ex){try{tasks.markFailed(id,get(id).status(),lease,ex.getClass().getSimpleName(),truncate(ex.getMessage(),500));}catch(RuntimeException suppressed){ex.addSuppressed(suppressed);}throw ex;}}
    private static String truncate(String value,int max){if(value==null)return "unspecified";return value.length()<=max?value:value.substring(0,max);}
    @Transactional @Audited(action="developer.task.approve",targetType="developer_task") public DeveloperAgentTask approve(long id,String operator){tasks.approve(id,operator);return get(id);}
    @Transactional @Audited(action="developer.task.cancel",targetType="developer_task") public DeveloperAgentTask cancel(long id,String operator){tasks.cancel(id,operator);return get(id);}
    @Transactional public int reclaimExpired(Duration lease){return tasks.reclaimExpired(Instant.now().minus(lease));}
}
