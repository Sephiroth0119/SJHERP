package com.sjherp.app.gap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.sjherp.domain.gap.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DeveloperAgentServiceTest {
    @Test void runnerIsOutsideTransactionAndEvidenceIsForwardedToCas() {
        var candidates=mock(GapIssueCandidateRepository.class); var tasks=mock(DeveloperAgentTaskRepository.class); var runner=mock(DeveloperAgentRunner.class);
        when(runner.available()).thenReturn(true);
        when(candidates.findById(2)).thenReturn(Optional.of(new GapIssueCandidate(2,"k","k",BusinessModule.GENERAL,GapSeverity.LOW,"title",List.of("scenario"),"expected","missing",List.of("GAP-1"),GapIssueStatus.SENT,1L,"https://issue",null,null,null,0,null,null,null)));
        when(tasks.claim(eq(9L), any())).thenReturn(Optional.of("lease-1"));
        when(tasks.findById(9)).thenReturn(Optional.of(task(9, DeveloperAgentTaskStatus.RUNNING)));
        when(runner.run(any(DeveloperAgentRunner.RunRequest.class))).thenReturn(new DeveloperAgentRunner.Result(List.of("code.java","test.java"),true,true,true,"ci://run/9","ok"));
        var service=new DeveloperAgentService(candidates,tasks,mock(GapRecordRepository.class),mock(GapRecordService.class),runner,mock(WorkspacePolicy.class));
        service.run(9,"admin");
        var request=org.mockito.ArgumentCaptor.forClass(DeveloperAgentRunner.RunRequest.class);
        verify(runner).run(request.capture());
        org.assertj.core.api.Assertions.assertThat(request.getValue().candidate().issueNumber()).isEqualTo(1L);
        verify(tasks).transition(eq(9L),eq(DeveloperAgentTaskStatus.RUNNING),eq(DeveloperAgentTaskStatus.TESTING),eq("lease-1"),anyList(),eq(true),eq(false),eq(false),isNull());
        verify(tasks).transition(eq(9L),eq(DeveloperAgentTaskStatus.TESTING),eq(DeveloperAgentTaskStatus.AWAITING_REVIEW),eq("lease-1"),anyList(),eq(true),eq(true),eq(true),eq("ci://run/9"));
    }
    @Test void startRejectsCandidateThatWasNotSent() {
        GapIssueCandidateRepository candidates=mock(GapIssueCandidateRepository.class);
        when(candidates.findById(7)).thenReturn(Optional.of(candidate(GapIssueStatus.APPROVED)));
        DeveloperAgentService service=new DeveloperAgentService(candidates, mock(DeveloperAgentTaskRepository.class), mock(GapRecordRepository.class), mock(GapRecordService.class), mock(DeveloperAgentRunner.class), mock(WorkspacePolicy.class));
        assertThatThrownBy(()->service.start(7,"admin")).isInstanceOf(GapIssueStateException.class);
        verifyNoInteractions(gaps(candidates));
    }
    private static GapRecordRepository gaps(GapIssueCandidateRepository ignored){return mock(GapRecordRepository.class);}
    private static DeveloperAgentTask task(long id, DeveloperAgentTaskStatus status){return new DeveloperAgentTask(id,2,"k",status,"codex/dev/k","C:/repo/k","FAKE","lease-1",1,List.of("pending"),false,false,false,null,false);}
    private static GapIssueCandidate candidate(GapIssueStatus status){return new GapIssueCandidate(7,"k","k",BusinessModule.GENERAL,GapSeverity.LOW,"t",List.of("s"),"e","m",List.of("GAP-1"),status,1L,"url",null,null,null,0,null,null,null);}
}
