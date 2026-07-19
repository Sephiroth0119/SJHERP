package com.sjherp.infra.persistence.gap;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V37ClosureFeedbackMigrationContractTest {
    @Test
    void definesAppendOnlyTaskIdempotencyAndEvidence() throws Exception {
        String sql = new String(getClass().getClassLoader()
                .getResourceAsStream("db/migration/V37__closure_feedback.sql").readAllBytes(), StandardCharsets.UTF_8);
        assertThat(sql).contains("CREATE TABLE closure_feedback", "task_id", "candidate_id",
                "evidence_reference", "evidence_summary", "UNIQUE KEY uk_closure_feedback_task");
        assertThat(sql.toLowerCase()).doesNotContain("delete from", "drop table");
    }

    @Test
    void leavesPublishedV36UntouchedAndOwnsTheParentKeyAlterInV37() throws Exception {
        String v36 = new String(getClass().getClassLoader()
                .getResourceAsStream("db/migration/V36__developer_agent_task.sql").readAllBytes(), StandardCharsets.UTF_8);
        String v37 = new String(getClass().getClassLoader()
                .getResourceAsStream("db/migration/V37__closure_feedback.sql").readAllBytes(), StandardCharsets.UTF_8);
        assertThat(v36).doesNotContain("uk_developer_task_tenant_id");
        assertThat(v37).contains("ALTER TABLE developer_agent_task", "ADD UNIQUE KEY uk_developer_task_tenant_id");
    }
}
