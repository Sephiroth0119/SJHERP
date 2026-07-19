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
        assertThat(tasks.claim(first.id(),Instant.now())).isEmpty();
        tasks.transition(first.id(),DeveloperAgentTaskStatus.RUNNING,DeveloperAgentTaskStatus.TESTING,lease,List.of("code.java","test.java"),true,false,false,null,"running");
        assertThatThrownBy(()->tasks.transition(first.id(),DeveloperAgentTaskStatus.TESTING,DeveloperAgentTaskStatus.AWAITING_REVIEW,"stale",List.of("code.java"),true,true,true,"ci", "stale" )).isInstanceOf(IllegalStateException.class);
        tasks.transition(first.id(),DeveloperAgentTaskStatus.TESTING,DeveloperAgentTaskStatus.AWAITING_REVIEW,lease,List.of("code.java","test.java"),true,true,true,"ci://1","done");
        tasks.approve(first.id(),"boss");
        assertThat(tasks.findById(first.id()).orElseThrow().status()).isEqualTo(DeveloperAgentTaskStatus.APPROVED);
    }

    private GapIssueCandidate candidate(String key){return new GapIssueCandidate(0,key,key,BusinessModule.GENERAL,GapSeverity.LOW,"title",List.of("scenario"),"expected","missing",List.of(),GapIssueStatus.SENT,1L,"https://issue",null,null,null,0,null,null,null);}
    private DeveloperAgentTask task(long candidateId,String key){return new DeveloperAgentTask(0,candidateId,key,DeveloperAgentTaskStatus.QUEUED,"codex/dev/x","C:/repo/x","FAKE",null,0,List.of(),false,false,false,null,false);}
}
