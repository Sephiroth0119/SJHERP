package com.sjherp.app.gap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.sjherp.domain.gap.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;

class DeveloperAgentServiceTest {
    @Test void runnerIsOutsideTransactionAndEvidenceIsForwardedToCas() {
        var candidates=mock(GapIssueCandidateRepository.class); var tasks=mock(DeveloperAgentTaskRepository.class); var runner=mock(DeveloperAgentRunner.class);
        when(runner.available()).thenReturn(true);
        GapIssueCandidate before = new GapIssueCandidate(2,"k","k",BusinessModule.GENERAL,GapSeverity.LOW,"old",List.of("scenario"),"expected","missing",List.of("GAP-1"),GapIssueStatus.SENT,1L,"https://issue",null,null,null,0,null,null,null);
        GapIssueCandidate after = new GapIssueCandidate(2,"k","k",BusinessModule.GENERAL,GapSeverity.LOW,"new",List.of("scenario"),"expected","missing",List.of("GAP-1"),GapIssueStatus.SENT,2L,"https://issue",null,null,null,0,null,null,null);
        when(candidates.findById(2)).thenReturn(Optional.of(before), Optional.of(after));
        when(tasks.claim(eq(9L), any())).thenReturn(Optional.of("lease-1"));
        when(tasks.findById(9)).thenReturn(Optional.of(task(9, DeveloperAgentTaskStatus.RUNNING)));
        when(runner.run(any(DeveloperAgentRunner.RunRequest.class))).thenAnswer(invocation -> { org.assertj.core.api.Assertions.assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return new DeveloperAgentRunner.Result(List.of("code.java","test.java"),true,true,true,"ci://run/9","ok"); });
        var service=new DeveloperAgentService(candidates,tasks,mock(GapRecordRepository.class),mock(GapRecordService.class),runner,mock(WorkspacePolicy.class));
        service.run(9,"admin");
        var request=org.mockito.ArgumentCaptor.forClass(DeveloperAgentRunner.RunRequest.class);
        verify(runner).run(request.capture());
        org.assertj.core.api.Assertions.assertThat(request.getValue().candidate().issueNumber()).isEqualTo(2L);
        InOrder order = inOrder(candidates, tasks);
        order.verify(candidates).findById(2);
        order.verify(tasks).claim(eq(9L), any());
        order.verify(candidates).findById(2);
        verify(tasks).transition(eq(9L),eq(DeveloperAgentTaskStatus.RUNNING),eq(DeveloperAgentTaskStatus.TESTING),eq("lease-1"),anyList(),eq(true),eq(false),eq(false),isNull(),eq("ok"));
        verify(tasks).transition(eq(9L),eq(DeveloperAgentTaskStatus.TESTING),eq(DeveloperAgentTaskStatus.AWAITING_REVIEW),eq("lease-1"),anyList(),eq(true),eq(true),eq(true),eq("ci://run/9"),eq("ok"));
    }
    @Test void runMapsRepositoryStateErrorsToConflictException() {
        GapIssueCandidateRepository candidates = mock(GapIssueCandidateRepository.class);
        when(candidates.findById(2)).thenReturn(Optional.of(candidate(GapIssueStatus.SENT)));
        DeveloperAgentTaskRepository tasks = mock(DeveloperAgentTaskRepository.class);
        when(tasks.claim(eq(9L), any())).thenReturn(Optional.of("lease-1"));
        when(tasks.findById(9)).thenReturn(Optional.of(task(9, DeveloperAgentTaskStatus.RUNNING)));
        doThrow(new IllegalStateException("stale" )).when(tasks).transition(anyLong(), any(), any(), anyString(), anyList(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any());
        DeveloperAgentRunner runner = mock(DeveloperAgentRunner.class);
        when(runner.available()).thenReturn(true);
        when(runner.run(any())).thenReturn(new DeveloperAgentRunner.Result(List.of("code.java"), true, true, true, "ci://1", "out"));
        var service = new DeveloperAgentService(candidates, tasks,
                mock(GapRecordRepository.class), mock(GapRecordService.class), runner, mock(WorkspacePolicy.class));
        assertThatThrownBy(() -> service.run(9, "admin")).isInstanceOf(DeveloperAgentTaskStateException.class);
    }
    @Test void startRejectsCandidateThatWasNotSent() {
        GapIssueCandidateRepository candidates=mock(GapIssueCandidateRepository.class);
        DeveloperAgentTaskRepository tasks=mock(DeveloperAgentTaskRepository.class);
        when(candidates.findById(7)).thenReturn(Optional.of(candidate(GapIssueStatus.APPROVED)));
        DeveloperAgentService service=new DeveloperAgentService(candidates, tasks, mock(GapRecordRepository.class), mock(GapRecordService.class), mock(DeveloperAgentRunner.class), mock(WorkspacePolicy.class));
        assertThatThrownBy(()->service.start(7,"admin")).isInstanceOf(GapIssueStateException.class);
        verifyNoInteractions(tasks);
    }
    @Test void startRejectsIllegalSourceBeforeCreatingTask() {
        GapIssueCandidateRepository candidates = mock(GapIssueCandidateRepository.class);
        DeveloperAgentTaskRepository tasks = mock(DeveloperAgentTaskRepository.class);
        GapRecordRepository gaps = mock(GapRecordRepository.class);
        GapIssueCandidate c = new GapIssueCandidate(7, "k", "k", BusinessModule.GENERAL, GapSeverity.LOW,
                "t", List.of("s"), "e", "m", List.of("GAP-1"), GapIssueStatus.SENT, 1L, "url", null, null, null, 0, null, null, null);
        when(candidates.findById(7)).thenReturn(Optional.of(c));
        GapRecord rejected = mock(GapRecord.class);
        when(rejected.getStatus()).thenReturn(GapStatus.RESOLVED);
        when(gaps.findByGapNo("GAP-1")).thenReturn(Optional.of(rejected));
        DeveloperAgentService service = new DeveloperAgentService(candidates, tasks, gaps,
                mock(GapRecordService.class), mock(DeveloperAgentRunner.class), mock(WorkspacePolicy.class));
        assertThatThrownBy(() -> service.start(7, "admin")).isInstanceOf(GapIssueStateException.class);
        verifyNoInteractions(tasks);
    }

    @Test void qualityFailuresForwardCompleteResultToFailureService() {
        GapIssueCandidateRepository candidates = mock(GapIssueCandidateRepository.class);
        DeveloperAgentTaskRepository tasks = mock(DeveloperAgentTaskRepository.class);
        DeveloperAgentRunner runner = mock(DeveloperAgentRunner.class);
        DeveloperAgentFailureService failures = mock(DeveloperAgentFailureService.class);
        when(runner.available()).thenReturn(true);
        when(tasks.claim(eq(9L), any())).thenReturn(Optional.of("lease-1"));
        when(tasks.findById(9)).thenReturn(Optional.of(task(9, DeveloperAgentTaskStatus.RUNNING)));
        when(candidates.findById(2)).thenReturn(Optional.of(candidate(GapIssueStatus.SENT)));
        DeveloperAgentRunner.Result result = new DeveloperAgentRunner.Result(List.of("code", "tests"), false, true, false, "ci://failed", "runner-output");
        when(runner.run(any())).thenReturn(result);
        DeveloperAgentService service = new DeveloperAgentService(candidates, tasks, mock(GapRecordRepository.class), mock(GapRecordService.class), runner, mock(WorkspacePolicy.class), failures);
        service.run(9, "admin");
        verify(failures).fail(eq(9L), eq(DeveloperAgentTaskStatus.RUNNING), eq("lease-1"), eq("QUALITY_GATE"), anyString(), eq(result.generatedArtifacts()), eq(false), eq(true), eq(false), eq("ci://failed"), eq("runner-output"), eq("admin"));
    }
    @Test void approveAndCancelWrapRepositoryStateErrors(){DeveloperAgentTaskRepository tasks=mock(DeveloperAgentTaskRepository.class);when(tasks.findById(1)).thenReturn(Optional.of(task(1,DeveloperAgentTaskStatus.AWAITING_REVIEW)));doThrow(new IllegalStateException("bad")).when(tasks).approve(1,"admin");doThrow(new IllegalStateException("bad")).when(tasks).cancel(1,"admin");var service=new DeveloperAgentService(mock(GapIssueCandidateRepository.class),tasks,mock(GapRecordRepository.class),mock(GapRecordService.class),mock(DeveloperAgentRunner.class),mock(WorkspacePolicy.class));org.assertj.core.api.Assertions.assertThatThrownBy(()->service.approve(1,"admin")).isInstanceOf(DeveloperAgentTaskStateException.class);org.assertj.core.api.Assertions.assertThatThrownBy(()->service.cancel(1,"admin")).isInstanceOf(DeveloperAgentTaskStateException.class);}
    private static DeveloperAgentTask task(long id, DeveloperAgentTaskStatus status){return new DeveloperAgentTask(id,2,"k",status,"codex/dev/k","C:/repo/k","FAKE","lease-1",1,List.of("pending"),false,false,false,null,false,null,null,null);}
    private static GapIssueCandidate candidate(GapIssueStatus status){return new GapIssueCandidate(7,"k","k",BusinessModule.GENERAL,GapSeverity.LOW,"t",List.of("s"),"e","m",List.of("GAP-1"),status,1L,"url",null,null,null,0,null,null,null);}
}
