package com.sjherp.app.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.app.notification.InAppNotificationChannel;
import com.sjherp.domain.consistency.ConsistencyCheckRun;
import com.sjherp.domain.consistency.ConsistencyCheckRunRepository;

class ConsistencyRunPersistenceServiceTest {

    private final ConsistencyCheckRunRepository repository = mock(ConsistencyCheckRunRepository.class);
    private final InAppNotificationChannel channel = mock(InAppNotificationChannel.class);
    private final ConsistencyRunPersistenceService service =
            new ConsistencyRunPersistenceService(repository, channel);

    @Test
    void savesReportThenInvokesOnlyInAppChannel() {
        ConsistencyCheckRun run = cleanRun();

        service.persist(run);

        InOrder order = inOrder(repository, channel);
        order.verify(repository).save(run);
        order.verify(channel).send(run);
        order.verifyNoMoreInteractions();
    }

    @Test
    void repositoryFailureStopsNotification() {
        ConsistencyCheckRun run = cleanRun();
        RuntimeException failure = new RuntimeException("db down");
        org.mockito.Mockito.doThrow(failure).when(repository).save(run);

        assertThatThrownBy(() -> service.persist(run)).isSameAs(failure);

        verifyNoInteractions(channel);
    }

    @Test
    void persistenceUsesIndependentTransaction() throws Exception {
        Transactional transactional = ConsistencyRunPersistenceService.class
                .getMethod("persist", ConsistencyCheckRun.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    private static ConsistencyCheckRun cleanRun() {
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        return ConsistencyCheckRun.completed(0, "CHK-202607-0001",
                ConsistencyCheckRun.TriggerType.MANUAL_API, "admin", now, now,
                ConsistencyCheckRun.AnalysisStatus.SKIPPED, null, List.of());
    }
}
