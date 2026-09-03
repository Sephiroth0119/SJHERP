package com.sjherp.infra.persistence.gap;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V36DeveloperAgentMigrationContractTest {
    @Test void migrationHasCandidateUniquenessAndLeaseFields() throws Exception {
        String sql = new String(getClass().getClassLoader().getResourceAsStream("db/migration/V36__developer_agent_task.sql").readAllBytes(), StandardCharsets.UTF_8);
        assertThat(sql).contains("CREATE TABLE developer_agent_task", "candidate_id", "lease_token", "ci_green", "human_approved", "UNIQUE KEY uk_developer_task_tenant_candidate");
    }
}
