package com.sjherp.app.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.consistency.ConsistencyCheckRun;
import com.sjherp.domain.consistency.ConsistencyCheckRunRepository;
import com.sjherp.domain.consistency.ConsistencyRunQuery;

class ConsistencyReportServiceTest {

    private final ConsistencyCheckRunRepository repository = mock(ConsistencyCheckRunRepository.class);
    private final ConsistencyReportService service = new ConsistencyReportService(repository);

    @Test
    void searchesTenantZeroWithValidatedPaging() {
        PageResult<ConsistencyCheckRun> expected = new PageResult<>(List.of(), 0, 2, 25);
        when(repository.search(0, new ConsistencyRunQuery(2, 25))).thenReturn(expected);

        assertThat(service.search(2, 25)).isSameAs(expected);
        verify(repository).search(0, new ConsistencyRunQuery(2, 25));
    }

    @Test
    void rejectsInvalidPagingBeforeRepositoryCall() {
        assertThatThrownBy(() -> service.search(0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(1, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getsTenantZeroDetailAndThrowsDedicatedNotFound() {
        ConsistencyCheckRun run = cleanRun();
        when(repository.findByRunNo(0, run.runNo())).thenReturn(Optional.of(run));

        assertThat(service.get(run.runNo())).isSameAs(run);
        assertThatThrownBy(() -> service.get("CHK-missing"))
                .isInstanceOf(ConsistencyReportNotFoundException.class);
        verify(repository).findByRunNo(0, run.runNo());
        verify(repository).findByRunNo(0, "CHK-missing");
    }

    @Test
    void reportQueriesAreReadOnlyTransactions() throws Exception {
        assertThat(ConsistencyReportService.class.getMethod("search", int.class, int.class)
                .getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(ConsistencyReportService.class.getMethod("get", String.class)
                .getAnnotation(Transactional.class).readOnly()).isTrue();
    }

    private static ConsistencyCheckRun cleanRun() {
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        return ConsistencyCheckRun.completed(0, "CHK-202607-0001",
                ConsistencyCheckRun.TriggerType.MANUAL_API, "admin", now, now,
                ConsistencyCheckRun.AnalysisStatus.SKIPPED, null, List.of());
    }
}
