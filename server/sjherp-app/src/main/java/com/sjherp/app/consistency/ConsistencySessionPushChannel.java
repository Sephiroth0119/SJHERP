package com.sjherp.app.consistency;

import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.sjherp.agent.reply.AgentReply;
import com.sjherp.agent.session.AgentMessage;
import com.sjherp.agent.session.AgentSession;
import com.sjherp.agent.session.AgentSessionRepository;
import com.sjherp.agent.session.MessageRole;
import com.sjherp.agent.session.SessionStatus;
import com.sjherp.domain.consistency.ConsistencyCheckRun;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.User;
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.infra.agent.AgentReplyJsonCodec;

/**
 * P0 一致性差异的会话主动推送。
 *
 * <p>只向启用中的 ADMIN/BOSS 的 ACTIVE 会话追加安全摘要；完整差异仍通过只读报告工具召回。
 * 消息带稳定来源标记，重复调度/重试不会重复追加同一报告。推送不进入业务写事务。
 */
@Service
public class ConsistencySessionPushChannel implements ConsistencyProactiveChannel {

    private static final Logger log = LoggerFactory.getLogger(ConsistencySessionPushChannel.class);
    private static final Set<Role> RECIPIENT_ROLES = Set.of(Role.ADMIN, Role.BOSS);
    private static final String MARKER_PREFIX = "[CONSISTENCY_REPORT_PUSH:";

    private final UserRepository users;
    private final AgentSessionRepository sessions;
    private final AgentReplyJsonCodec codec;

    public ConsistencySessionPushChannel(UserRepository users,
                                         AgentSessionRepository sessions,
                                         AgentReplyJsonCodec codec) {
        this.users = Objects.requireNonNull(users, "users 不能为空");
        this.sessions = Objects.requireNonNull(sessions, "sessions 不能为空");
        this.codec = Objects.requireNonNull(codec, "codec 不能为空");
    }

    @Override
    public void send(ConsistencyCheckRun run) {
        Objects.requireNonNull(run, "run 不能为空");
        if (run.status() != ConsistencyCheckRun.Status.COMPLETED || run.errorCount() == 0) {
            return;
        }
        String marker = marker(run.runNo());
        for (User user : users.findAll()) {
            if (!eligible(user)) {
                continue;
            }
            String userId = String.valueOf(user.getId());
            for (AgentSession session : sessions.findByUserId(userId)) {
                if (session.getStatus() != SessionStatus.ACTIVE || containsMarker(session, marker)) {
                    continue;
                }
                session.append(AgentMessage.assistant(codec.toJson(AgentReply.text(message(run, marker)))));
                sessions.save(session);
            }
        }
    }

    private static boolean eligible(User user) {
        return user != null && user.getId() != null && user.isEnabled()
                && user.getRoles().stream().anyMatch(RECIPIENT_ROLES::contains);
    }

    private static boolean containsMarker(AgentSession session, String marker) {
        return session.getMessages().stream()
                .filter(message -> message.role() == MessageRole.ASSISTANT)
                .anyMatch(message -> message.content() != null && message.content().contains(marker));
    }

    private static String marker(String runNo) {
        return MARKER_PREFIX + runNo + "]";
    }

    private static String message(ConsistencyCheckRun run, String marker) {
        return marker + "\n"
                + "【一致性检查主动提醒】发现 P0 不一致。运行编号=" + run.runNo()
                + "，ERROR=" + run.errorCount() + "，WARN=" + run.warnCount()
                + "。系统未自动修复，请在本会话中询问“查看本次一致性报告”以召回明细并解释。";
    }
}
