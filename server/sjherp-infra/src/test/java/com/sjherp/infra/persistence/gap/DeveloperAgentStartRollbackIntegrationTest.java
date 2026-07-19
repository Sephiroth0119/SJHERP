package com.sjherp.infra.persistence.gap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sjherp.infra.persistence.MySqlContainerTestBase;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies the real InnoDB transaction boundary used by start's task/gap changes. */
class DeveloperAgentStartRollbackIntegrationTest extends MySqlContainerTestBase {
    @Test
    void taskAndFirstGapChangeRollBackWhenSecondSourceGapFails() {
        String suffix = uniqueSuffix();
        String gapOne = "GAP-T09-1-" + suffix;
        String gapTwo = "GAP-T09-2-" + suffix;
        insertGap(gapOne);
        insertGap(gapTwo);
        Long candidateId = jdbc.queryForObject(
                "INSERT INTO gap_issue_candidate(tenant_id,idempotency_key,cluster_key,business_module,severity,title,scenario_samples,expected_behavior,missing_capability,status,created_at,updated_at) "
                        + "VALUES(0,?,?, 'GENERAL','LOW','t','[\"s\"]','e','m','SENT',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); SELECT LAST_INSERT_ID()",
                Long.class, "rollback-" + suffix, "rollback-cluster-" + suffix);
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO developer_agent_task(tenant_id,candidate_id,idempotency_key,status,branch_name,workspace_path,runner_kind,attempt_count,ci_green,human_approved,generated_artifacts,targeted_tests_green,full_tests_green,created_by,created_at,updated_at) VALUES(0,?,?,?,?,?, 'DISABLED',0,false,false,'[]',false,false,'test',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                    candidateId, "task-" + suffix, "codex/dev/rollback-" + suffix, "workspace/" + suffix);
            jdbc.update("UPDATE gap_record SET status='IN_DEVELOPMENT' WHERE tenant_id=0 AND gap_no=?", gapOne);
            jdbc.update("UPDATE gap_record SET status='IN_DEVELOPMENT' WHERE tenant_id=0 AND gap_no='missing-' + ?", gapTwo);
        })).isInstanceOf(Exception.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM developer_agent_task WHERE idempotency_key=?", Integer.class, "task-" + suffix)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM gap_record WHERE gap_no=?", String.class, gapOne)).isEqualTo("TRIAGED");
    }

    private void insertGap(String gapNo) {
        jdbc.update("INSERT INTO gap_record(tenant_id,gap_no,title,scenario,expected_behavior,missing_capability,business_module,severity,status,reporter,created_by,created_at,updated_by,updated_at) VALUES(0,?,?,?,?,?,'GENERAL','LOW','TRIAGED','test','test',UTC_TIMESTAMP(6),'test',UTC_TIMESTAMP(6))",
                gapNo, "title", "scenario", "expected", "missing");
    }
}
