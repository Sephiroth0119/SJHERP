package com.sjherp.infra.persistence.gap;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.concurrent.*;
import com.sjherp.infra.persistence.MySqlContainerTestBase;
import org.junit.jupiter.api.Test;

class JdbcClosureFeedbackRepositoryIntegrationTest extends MySqlContainerTestBase {
    @Test
    void uniqueClaimAllowsExactlyOneWinnerUnderConcurrency() throws Exception {
        JdbcClosureFeedbackRepository repository = new JdbcClosureFeedbackRepository(jdbc);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> claim = () -> repository.claim(0, 9001, "commit-" + uniqueSuffix(), "done", "admin");
            Future<Boolean> first = pool.submit(claim);
            Future<Boolean> second = pool.submit(claim);
            assertThat(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
            jdbc.update("DELETE FROM closure_feedback WHERE task_id=?", 0);
        }
    }
}
