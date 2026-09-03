package com.sjherp.app.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.consistency.ConsistencyCheckRun;
import com.sjherp.domain.consistency.ConsistencyFinding;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.User;
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.domain.notification.SystemNotification;
import com.sjherp.domain.notification.SystemNotificationRepository;

class InAppNotificationChannelTest {

    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UserRepository users = mock(UserRepository.class);
    private final SystemNotificationRepository notifications = mock(SystemNotificationRepository.class);
    private final InAppNotificationChannel channel = new InAppNotificationChannel(users, notifications, CLOCK);

    @Test
    void sendsOnlyToEnabledAdminsAndBossesAndIsIdempotent() {
        when(users.findAll()).thenReturn(List.of(enabledUser(1, Role.ADMIN), enabledUser(2, Role.BOSS),
                enabledUser(3, Role.SALES), disabledUser(4, Role.ADMIN)));
        when(notifications.existsBySource(anyLong(), anyLong(), any(), anyString()))
                .thenReturn(false, false, true, true);

        channel.send(nonCleanRun());
        channel.send(nonCleanRun());

        ArgumentCaptor<SystemNotification> saved = ArgumentCaptor.forClass(SystemNotification.class);
        verify(notifications, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(SystemNotification::recipientUserId)
                .containsExactly(1L, 2L);
    }

    @Test
    void cleanCompletedRunCreatesNoNotificationEvenWhenAnalysisFailed() {
        channel.send(cleanRunWithFailedAnalysis());

        verifyNoInteractions(users, notifications);
    }

    @Test
    void notificationContainsOnlySafeRunNumberSourceAndCounts() {
        when(users.findAll()).thenReturn(List.of(enabledUser(1, Role.ADMIN)));
        when(notifications.existsBySource(anyLong(), anyLong(), any(), anyString())).thenReturn(false);

        channel.send(nonCleanRun());

        ArgumentCaptor<SystemNotification> saved = ArgumentCaptor.forClass(SystemNotification.class);
        verify(notifications).save(saved.capture());
        SystemNotification notification = saved.getValue();
        assertThat(notification.category()).isEqualTo(SystemNotification.Category.CONSISTENCY);
        assertThat(notification.severity()).isEqualTo(SystemNotification.Severity.ERROR);
        assertThat(notification.sourceType()).isEqualTo(SystemNotification.SourceType.CONSISTENCY_REPORT);
        assertThat(notification.sourceRef()).isEqualTo("CHK-202607-0002");
        assertThat(notification.content())
                .contains("CHK-202607-0002", "MANUAL_API", "总数=1", "错误=1", "警告=0", "提示=0")
                .doesNotContain("full finding message", "admin", "analysis");
        assertThat(notification.createdAt()).isEqualTo(NOW);
    }

    @Test
    void deterministicFailedRunNotifiesAsErrorDespiteZeroFindingCounts() {
        when(users.findAll()).thenReturn(List.of(enabledUser(2, Role.BOSS)));
        when(notifications.existsBySource(anyLong(), anyLong(), any(), anyString())).thenReturn(false);

        channel.send(failedRun());

        ArgumentCaptor<SystemNotification> saved = ArgumentCaptor.forClass(SystemNotification.class);
        verify(notifications).save(saved.capture());
        assertThat(saved.getValue().severity()).isEqualTo(SystemNotification.Severity.ERROR);
        assertThat(saved.getValue().content()).contains("总数=0", "错误=0")
                .doesNotContain("IllegalStateException", "database password");
    }

    private static User enabledUser(long id, Role... roles) {
        return user(id, ArchiveStatus.ENABLED, roles);
    }

    private static User disabledUser(long id, Role... roles) {
        return user(id, ArchiveStatus.DISABLED, roles);
    }

    private static User user(long id, ArchiveStatus status, Role... roles) {
        return User.restore(id, "user" + id, "User " + id, "hash", Set.of(roles), status,
                "admin", NOW, "admin", NOW);
    }

    private static ConsistencyCheckRun cleanRunWithFailedAnalysis() {
        return ConsistencyCheckRun.completed(0, "CHK-202607-0001",
                ConsistencyCheckRun.TriggerType.AGENT, "agent:7", NOW, NOW,
                ConsistencyCheckRun.AnalysisStatus.FAILED, null, List.of());
    }

    private static ConsistencyCheckRun nonCleanRun() {
        ConsistencyFinding finding = new ConsistencyFinding(1, "CORE_SQL_ASSERTIONS",
                "LEDGER_COST", "warehouse=1", BigDecimal.ONE, BigDecimal.ZERO,
                ConsistencyFinding.Severity.ERROR, "full finding message");
        return ConsistencyCheckRun.completed(0, "CHK-202607-0002",
                ConsistencyCheckRun.TriggerType.MANUAL_API, "admin", NOW, NOW,
                ConsistencyCheckRun.AnalysisStatus.SUCCEEDED, "analysis", List.of(finding));
    }

    private static ConsistencyCheckRun failedRun() {
        return ConsistencyCheckRun.failed(0, "CHK-202607-0003",
                ConsistencyCheckRun.TriggerType.SCHEDULED, "system:consistency-scheduler",
                NOW, NOW, "IllegalStateException");
    }
}
