package com.sjherp.infra.persistence.gap;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.concurrent.*;
import com.sjherp.infra.persistence.MySqlContainerTestBase;
import org.junit.jupiter.api.Test;

class JdbcClosureFeedbackRepositoryIntegrationTest extends MySqlContainerTestBase {
    @Test
    void uniqueClaimAllowsExactlyOneWinnerUnderConcurrency() throws Exception {
        jdbc.update("""
                INSERT INTO gap_issue_candidate(id,tenant_id,idempotency_key,cluster_key,business_module,severity,title,
                    scenario_samples,expected_behavior,missing_capability,status,created_at,updated_at)
                VALUES(9001,0,'it-closure','it-closure','test','P1','closure',JSON_ARRAY('sample'),'expected','missing','SENT',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        jdbc.update("""
                INSERT INTO developer_agent_task(id,tenant_id,candidate_id,idempotency_key,status,branch_name,workspace_path,
                    runner_kind,generated_artifacts,created_by,created_at,updated_at)
                VALUES(9002,0,9001,'it-closure-task','APPROVED','it','it','FAKE',JSON_ARRAY(),'it',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        JdbcClosureFeedbackRepository repository = new JdbcClosureFeedbackRepository(jdbc);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> claim = () -> repository.claim(9002, 9001, "commit-1", "done", "admin");
            Future<Boolean> first = pool.submit(claim);
            Future<Boolean> second = pool.submit(claim);
            assertThat(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
            jdbc.update("DELETE FROM closure_feedback WHERE task_id=?", 9002);
            jdbc.update("DELETE FROM developer_agent_task WHERE id=?", 9002);
            jdbc.update("DELETE FROM gap_issue_candidate WHERE id=?", 9001);
        }
    }
}
