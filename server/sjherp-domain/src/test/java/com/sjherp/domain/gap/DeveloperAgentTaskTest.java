package com.sjherp.domain.gap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import java.util.List;

class DeveloperAgentTaskTest {
    private DeveloperAgentTask task() {
        return new DeveloperAgentTask(1, 2, "k", DeveloperAgentTaskStatus.QUEUED,
                "codex/dev/k", "C:/safe/k", "FAKE", null, 0, List.of("code", "tests"), false, false, false, null, false, null, null, null);
    }

    @Test void onlyGreenCiCanBeApproved() {
        DeveloperAgentTask awaiting = task().transitionTo(DeveloperAgentTaskStatus.RUNNING)
                .transitionTo(DeveloperAgentTaskStatus.TESTING)
                .transitionTo(DeveloperAgentTaskStatus.AWAITING_REVIEW);
        assertThatThrownBy(awaiting::approve).isInstanceOf(IllegalStateException.class);
    }

    @Test void invalidTransitionIsRejected() {
        assertThatThrownBy(() -> task().transitionTo(DeveloperAgentTaskStatus.APPROVED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void auditTargetExposesSafeIdentityAndSummary() {
        DeveloperAgentTask task = task();
        assertThat(task.auditTargetId()).isEqualTo(1L);
        assertThat(task.auditTargetCode()).isEqualTo("k");
        assertThat(task.auditSummary()).contains("candidate=2", "status=QUEUED", "runner=FAKE");
        assertThat(task.auditSummary()).doesNotContain("C:/safe/k", "lease", "ciEvidence");
        DeveloperAgentTask draft = new DeveloperAgentTask(0, 2, "draft", DeveloperAgentTaskStatus.QUEUED,
                "codex/dev/draft", "C:/safe/draft", "FAKE", "secret-token", 0, List.of(), false, false, false,
                "ci://secret", false, null, null, null);
        assertThat(draft.auditTargetId()).isNull();
    }
}
