package com.sjherp.app.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.sjherp.agent.session.AgentMessage;
import com.sjherp.agent.session.AgentSession;
import com.sjherp.agent.session.InMemoryAgentSessionRepository;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.consistency.ConsistencyCheckRun;
import com.sjherp.domain.consistency.ConsistencyFinding;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.User;
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.infra.agent.AgentReplyJsonCodec;

class ConsistencySessionPushChannelTest {

    private static final Instant NOW = Instant.parse("2026-07-19T01:02:03Z");
    private final UserRepository users = mock(UserRepository.class);
    private final InMemoryAgentSessionRepository sessions = new InMemoryAgentSessionRepository();
    private final ConsistencySessionPushChannel channel = new ConsistencySessionPushChannel(
            users, sessions, new AgentReplyJsonCodec());

    @Test
    void pushesOnlyP0ToEnabledAdminActiveSessionAndIsIdempotent() {
        User admin = mockUser(7L, Role.ADMIN, true);
        User sales = mockUser(8L, Role.SALES, true);
        when(users.findAll()).thenReturn(List.of(admin, sales));
        AgentSession active = new AgentSession("session-admin", "7");
        AgentSession closed = new AgentSession("session-closed", "7");
        closed.close();
        AgentSession salesSession = new AgentSession("session-sales", "8");
        sessions.save(active);
        sessions.save(closed);
        sessions.save(salesSession);

        ConsistencyCheckRun run = p0Run();
        channel.send(run);
        channel.send(run);

        assertThat(sessions.findById("session-admin").orElseThrow().getMessages())
                .hasSize(1)
                .extracting(AgentMessage::content)
                .singleElement()
                .asString()
                .contains("CHK-202607-0001", "P0", "ERROR=1")
                .doesNotContain("库存成本不一致");
        assertThat(sessions.findById("session-closed").orElseThrow().getMessages()).isEmpty();
        assertThat(sessions.findById("session-sales").orElseThrow().getMessages()).isEmpty();
    }

    @Test
    void doesNotPushCleanOrWarnOnlyReport() {
        User admin = mockUser(7L, Role.ADMIN, true);
        when(users.findAll()).thenReturn(List.of(admin));
        AgentSession session = new AgentSession("session-admin", "7");
        sessions.save(session);

        channel.send(ConsistencyCheckRun.completed(0, "CHK-CLEAN",
                ConsistencyCheckRun.TriggerType.SCHEDULED, "system:consistency-scheduler", NOW, NOW,
                ConsistencyCheckRun.AnalysisStatus.SKIPPED, null, List.of()));
        channel.send(ConsistencyCheckRun.completed(0, "CHK-WARN",
                ConsistencyCheckRun.TriggerType.SCHEDULED, "system:consistency-scheduler", NOW, NOW,
                ConsistencyCheckRun.AnalysisStatus.SKIPPED, null,
                List.of(new ConsistencyFinding(1, "CORE", "CHECK", "x", BigDecimal.ONE,
                        BigDecimal.ZERO, ConsistencyFinding.Severity.WARN, "warn"))));

        assertThat(sessions.findById("session-admin").orElseThrow().getMessages()).isEmpty();
    }

    private static User mockUser(long id, Role role, boolean enabled) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.isEnabled()).thenReturn(enabled);
        when(user.getRoles()).thenReturn(EnumSet.of(role));
        return user;
    }

    private static ConsistencyCheckRun p0Run() {
        return ConsistencyCheckRun.completed(0, "CHK-202607-0001",
                ConsistencyCheckRun.TriggerType.SCHEDULED, "system:consistency-scheduler", NOW, NOW,
                ConsistencyCheckRun.AnalysisStatus.SKIPPED, null,
                List.of(new ConsistencyFinding(1, "CORE_SQL_ASSERTIONS", "LEDGER_COST",
                        "warehouse=1,product=2", new BigDecimal("10.123456"),
                        new BigDecimal("9.000000"), ConsistencyFinding.Severity.ERROR, "库存成本不一致")));
    }
}
