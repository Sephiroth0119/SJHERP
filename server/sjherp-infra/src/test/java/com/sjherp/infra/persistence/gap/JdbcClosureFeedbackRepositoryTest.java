package com.sjherp.infra.persistence.gap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcClosureFeedbackRepositoryTest {
    @Test
    void claimUsesUniqueKeyInsertAndDistinguishesWinnerFromDuplicate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcClosureFeedbackRepository repository = new JdbcClosureFeedbackRepository(jdbc);
        when(jdbc.update(contains("ON DUPLICATE KEY UPDATE"), any(), any(), any(), any(), any(), any())).thenReturn(1, 0);

        assertThat(repository.claim(7, 8, "commit-1", "done", "admin")).isTrue();
        assertThat(repository.claim(7, 8, "commit-1", "done", "admin")).isFalse();
        verify(jdbc, times(2)).update(contains("ON DUPLICATE KEY UPDATE"), any(), any(), any(), any(), any(), any());
    }
}
