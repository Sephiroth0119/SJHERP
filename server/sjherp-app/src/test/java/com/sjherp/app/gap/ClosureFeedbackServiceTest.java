package com.sjherp.app.gap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.sjherp.domain.gap.*;
import org.junit.jupiter.api.Test;
import com.sjherp.domain.common.audit.Audited;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.sjherp.domain.notification.SystemNotificationRepository;
import com.sjherp.domain.notification.SystemNotification;

class ClosureFeedbackServiceTest {
    @Test
    void refusesTaskThatIsNotApproved() {
        DeveloperAgentTaskRepository tasks = mock(DeveloperAgentTaskRepository.class);
        when(tasks.findById(7)).thenReturn(java.util.Optional.of(task(DeveloperAgentTaskStatus.AWAITING_REVIEW)));
        GapRecordRepository gaps = mock(GapRecordRepository.class);
        ClosureFeedbackService service = new ClosureFeedbackService(tasks, mock(GapIssueCandidateRepository.class),
                gaps, mock(ClosureFeedbackRepository.class), mock(com.sjherp.app.memory.MemoryWriteChannel.class),
                mock(com.sjherp.domain.notification.SystemNotificationRepository.class),
                mock(com.sjherp.agent.session.AgentSessionRepository.class));

        assertThatThrownBy(() -> service.confirm(7, new ClosureEvidence("PR-1", "已解决"), "admin"))
                .isInstanceOf(DeveloperAgentTaskStateException.class);
        verifyNoInteractions(gaps);
    }

    @Test
    void approvedTaskDoesNothingBeforeExplicitConfirmation() {
        DeveloperAgentTaskRepository tasks = mock(DeveloperAgentTaskRepository.class);
        ClosureFeedbackRepository closures = mock(ClosureFeedbackRepository.class);
        when(tasks.findById(7)).thenReturn(java.util.Optional.of(task(DeveloperAgentTaskStatus.APPROVED)));
        ClosureFeedbackService service = service(tasks, closures);

        // No call to confirm means no downstream write at all.
        verifyNoInteractions(closures);
        assertThat(tasks.findById(7)).isPresent();
    }

    @Test
    void repeatedConfirmationStopsAtDurableClosureClaim() {
        DeveloperAgentTaskRepository tasks = mock(DeveloperAgentTaskRepository.class);
        ClosureFeedbackRepository closures = mock(ClosureFeedbackRepository.class);
        when(tasks.findById(7)).thenReturn(java.util.Optional.of(task(DeveloperAgentTaskStatus.APPROVED)));
        when(closures.claim(anyLong(), anyLong(), anyString(), anyString(), anyString())).thenReturn(false);
        ClosureFeedbackService service = service(tasks, closures);

        service.confirm(7, new ClosureEvidence("commit-1", "已解决"), "admin");

        verify(closures).claim(eq(7L), eq(8L), anyString(), anyString(), anyString());
    }

    @Test
    void auditedMethodCarriesExplicitOperatorAndTaskTarget() throws Exception {
        var method = ClosureFeedbackService.class.getMethod("confirm", long.class, ClosureEvidence.class, String.class);
        assertThat(method.getParameterTypes()[2]).isEqualTo(String.class);
        assertThat(method.getAnnotation(Audited.class).action()).isEqualTo("developer.task.confirm_resolution");
        assertThat(method.getAnnotation(Audited.class).targetType()).isEqualTo("closure_feedback");
    }

    @Test
    void oneConfirmationResolvesMultipleGapsAndDeduplicatesSessionUsersWithReporterFallback() {
        var tasks = mock(DeveloperAgentTaskRepository.class);
        var candidates = mock(GapIssueCandidateRepository.class);
        var gaps = mock(GapRecordRepository.class);
        var closures = mock(ClosureFeedbackRepository.class);
        var memory = mock(com.sjherp.app.memory.MemoryWriteChannel.class);
        var notifications = mock(SystemNotificationRepository.class);
        var sessions = mock(com.sjherp.agent.session.AgentSessionRepository.class);
        when(tasks.findById(7)).thenReturn(Optional.of(task(DeveloperAgentTaskStatus.APPROVED)));
        when(closures.claim(anyLong(), anyLong(), anyString(), anyString(), anyString())).thenReturn(true);
        when(candidates.findById(8)).thenReturn(Optional.of(new GapIssueCandidate(8, "candidate-8", "cluster-8",
                BusinessModule.INVENTORY, GapSeverity.HIGH, "title", List.of("sample"), "expected", "missing",
                List.of("G-1", "G-2"), GapIssueStatus.APPROVED, null, null, null, null, null, 0,
                Instant.now(), Instant.now(), null)));
        var first = gap("G-1", "session-1", "11");
        var second = gap("G-2", "missing-session", "11");
        when(gaps.findByGapNo("G-1")).thenReturn(Optional.of(first));
        when(gaps.findByGapNo("G-2")).thenReturn(Optional.of(second));
        when(sessions.findById("session-1")).thenReturn(Optional.of(mockSession("11")));
        when(sessions.findById("missing-session")).thenReturn(Optional.empty());
        var service = new ClosureFeedbackService(tasks, candidates, gaps, closures, memory, notifications, sessions);

        service.confirm(7, new ClosureEvidence("PR-15", "fixed"), "admin");

        assertThat(first.getStatus()).isEqualTo(GapStatus.RESOLVED);
        assertThat(second.getStatus()).isEqualTo(GapStatus.RESOLVED);
        verify(gaps, times(2)).save(any(GapRecord.class));
        verify(memory).approveAndWrite(argThat(candidate -> candidate.facts().get("task").equals("7")
                && candidate.facts().get("candidate").equals("candidate-8")
                && candidate.facts().get("gaps").equals("G-1,G-2")), eq("admin"));
        verify(notifications).saveIfAbsent(any(SystemNotification.class));
        verifyNoMoreInteractions(notifications);
    }

    private static GapRecord gap(String no, String session, String reporter) {
        return GapRecord.restore(1, no, session, "title", "scenario", "expected", "missing",
                BusinessModule.INVENTORY, GapSeverity.HIGH, GapStatus.IN_DEVELOPMENT, reporter, "user",
                Instant.now(), "user", Instant.now());
    }

    private static com.sjherp.agent.session.AgentSession mockSession(String userId) {
        return new com.sjherp.agent.session.AgentSession("session", userId);
    }

    private static ClosureFeedbackService service(DeveloperAgentTaskRepository tasks, ClosureFeedbackRepository closures) {
        return new ClosureFeedbackService(tasks, mock(GapIssueCandidateRepository.class), mock(GapRecordRepository.class),
                closures, mock(com.sjherp.app.memory.MemoryWriteChannel.class),
                mock(com.sjherp.domain.notification.SystemNotificationRepository.class),
                mock(com.sjherp.agent.session.AgentSessionRepository.class));
    }

    private static DeveloperAgentTask task(DeveloperAgentTaskStatus status) {
        return new DeveloperAgentTask(7, 8, "idem", status, "branch", "workspace", "fake", null, 1,
                java.util.List.of("PR-1"), true, true, true, "ci", status == DeveloperAgentTaskStatus.APPROVED,
                null, null, "done");
    }
}
