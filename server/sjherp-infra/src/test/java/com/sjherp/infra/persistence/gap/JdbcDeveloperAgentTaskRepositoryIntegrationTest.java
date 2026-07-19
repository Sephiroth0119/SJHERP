package com.sjherp.infra.persistence.gap;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.gap.*;
import com.sjherp.infra.persistence.MySqlContainerTestBase;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class JdbcDeveloperAgentTaskRepositoryIntegrationTest extends MySqlContainerTestBase {
    private final JdbcGapIssueCandidateRepository candidates=new JdbcGapIssueCandidateRepository(jdbc,new ObjectMapper());
    private final JdbcDeveloperAgentTaskRepository tasks=new JdbcDeveloperAgentTaskRepository(jdbc,new ObjectMapper());

    @Test void idempotentCreateCasLifecycleAndApprovalEvidence() {
        GapIssueCandidate c=candidates.upsert(candidate("dev-"+uniqueSuffix()));
        DeveloperAgentTask initial=task(c.id(),"k-"+uniqueSuffix());
        DeveloperAgentTask first=tasks.createIfAbsent(initial,"boss");
        assertThat(tasks.createIfAbsent(initial,"boss").id()).isEqualTo(first.id());
        String lease=tasks.claim(first.id(),Instant.now()).orElseThrow();
        assertThat(tasks.findById(first.id()).orElseThrow().status()).isEqualTo(DeveloperAgentTaskStatus.RUNNING);
        assertThat(tasks.claim(first.id(),Instant.now())).isEmpty();
        assertThatThrownBy(()->tasks.approve(first.id(),"boss")).isInstanceOf(IllegalStateException.class);
        tasks.transition(first.id(),DeveloperAgentTaskStatus.RUNNING,DeveloperAgentTaskStatus.TESTING,lease,List.of("code.java","test.java"),true,false,false,null,"running"); assertThat(tasks.findById(first.id()).orElseThrow().runnerOutputSummary()).isEqualTo("running");
        assertThatThrownBy(()->tasks.transition(first.id(),DeveloperAgentTaskStatus.TESTING,DeveloperAgentTaskStatus.AWAITING_REVIEW,"stale",List.of("code.java"),true,true,true,"ci", "stale" )).isInstanceOf(IllegalStateException.class);
        tasks.transition(first.id(),DeveloperAgentTaskStatus.TESTING,DeveloperAgentTaskStatus.AWAITING_REVIEW,lease,List.of("code.java","test.java"),true,true,true,"ci://1","done");
        tasks.approve(first.id(),"boss");
        assertThat(tasks.findById(first.id()).orElseThrow().status()).isEqualTo(DeveloperAgentTaskStatus.APPROVED);
    }

    @Test void foreignCandidateAndStaleFailureAreRejectedAndAttemptsAreBounded(){
        assertThatThrownBy(()->tasks.createIfAbsent(task(999999,"fk-"+uniqueSuffix()),"boss")).isInstanceOf(Exception.class);
        GapIssueCandidate c=candidates.upsert(candidate("attempt-"+uniqueSuffix())); DeveloperAgentTask t=tasks.createIfAbsent(task(c.id(),"attempt-key-"+uniqueSuffix()),"boss");
        String lease=tasks.claim(t.id(),Instant.now()).orElseThrow(); assertThatThrownBy(()->tasks.markFailed(t.id(),DeveloperAgentTaskStatus.RUNNING,"stale","X","bad")).isInstanceOf(IllegalStateException.class); tasks.markFailed(t.id(),DeveloperAgentTaskStatus.RUNNING,lease,"X","bad");
        for(int i=0;i<2;i++){String next=tasks.claim(t.id(),Instant.now()).orElseThrow();tasks.markFailed(t.id(),DeveloperAgentTaskStatus.RUNNING,next,"X","bad");} assertThat(tasks.claim(t.id(),Instant.now())).isEmpty();
    }

    @Test void runningAndTestingLeasesAreReclaimedWithFailureEvidence(){
        GapIssueCandidate c=candidates.upsert(candidate("reclaim-"+uniqueSuffix())); DeveloperAgentTask t=tasks.createIfAbsent(task(c.id(),"reclaim-key-"+uniqueSuffix()),"boss");
        String lease=tasks.claim(t.id(),Instant.now()).orElseThrow(); jdbc.update("UPDATE developer_agent_task SET updated_at=? WHERE id=?",LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20),t.id()); assertThat(tasks.reclaimExpired(Instant.now().minus(Duration.ofMinutes(10)))).isEqualTo(1); assertThat(tasks.findById(t.id()).orElseThrow().failureType()).isEqualTo("LEASE_EXPIRED"); assertThat(tasks.findById(t.id()).orElseThrow().failureSummary()).isNotBlank();
        String retry=tasks.claim(t.id(),Instant.now()).orElseThrow(); tasks.transition(t.id(),DeveloperAgentTaskStatus.RUNNING,DeveloperAgentTaskStatus.TESTING,retry,List.of("code"),true,false,false,null,"out"); jdbc.update("UPDATE developer_agent_task SET updated_at=? WHERE id=?",LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20),t.id()); assertThat(tasks.reclaimExpired(Instant.now().minus(Duration.ofMinutes(10)))).isEqualTo(1); assertThat(tasks.findById(t.id()).orElseThrow().status()).isEqualTo(DeveloperAgentTaskStatus.FAILED);
    }

    private GapIssueCandidate candidate(String key){return new GapIssueCandidate(0,key,key,BusinessModule.GENERAL,GapSeverity.LOW,"title",List.of("scenario"),"expected","missing",List.of(),GapIssueStatus.SENT,1L,"https://issue",null,null,null,0,null,null,null);}
    private DeveloperAgentTask task(long candidateId,String key){return new DeveloperAgentTask(0,candidateId,key,DeveloperAgentTaskStatus.QUEUED,"codex/dev/x","C:/repo/x","FAKE",null,0,List.of(),false,false,false,null,false,null,null,null);}
}
