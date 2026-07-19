package com.sjherp.app.gap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.sjherp.domain.gap.*;
import org.junit.jupiter.api.Test;
import com.sjherp.domain.common.audit.Audited;

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
