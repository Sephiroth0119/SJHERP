package com.sjherp.app.gap;

import com.sjherp.app.memory.MemoryWriteChannel;
import com.sjherp.agent.session.AgentSessionRepository;
import com.sjherp.domain.gap.*;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.memory.*;
import com.sjherp.domain.notification.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

public class ClosureFeedbackService {
    private final DeveloperAgentTaskRepository tasks;
    private final GapIssueCandidateRepository candidates;
    private final GapRecordRepository gaps;
    private final ClosureFeedbackRepository closures;
    private final MemoryWriteChannel memory;
    private final SystemNotificationRepository notifications;
    private final AgentSessionRepository sessions;

    public ClosureFeedbackService(DeveloperAgentTaskRepository tasks, GapIssueCandidateRepository candidates,
            GapRecordRepository gaps, ClosureFeedbackRepository closures, MemoryWriteChannel memory,
            SystemNotificationRepository notifications, AgentSessionRepository sessions) {
        this.tasks = tasks; this.candidates = candidates; this.gaps = gaps; this.closures = closures;
        this.memory = Objects.requireNonNull(memory); this.notifications = Objects.requireNonNull(notifications);
        this.sessions = Objects.requireNonNull(sessions);
    }

    @Transactional
    @Audited(action = "developer.task.confirm_resolution", targetType = "closure_feedback")
    public void confirm(long taskId, ClosureEvidence evidence) {
        DeveloperAgentTask task = tasks.findById(taskId).orElseThrow(() -> new DeveloperAgentTaskNotFoundException(taskId));
        if (task.status() != DeveloperAgentTaskStatus.APPROVED) throw new DeveloperAgentTaskStateException("task must be APPROVED before closure");
        if (!closures.claim(taskId, task.candidateId(), evidence.reference(), evidence.summary(), evidence.operator())) return;
        GapIssueCandidate candidate = candidates.findById(task.candidateId()).orElseThrow(() -> new GapIssueNotFoundException(task.candidateId()));
        List<GapRecord> source = candidate.sourceGapNos().stream().map(no -> gaps.findByGapNo(no).orElseThrow(() -> new GapRecordNotFoundException(no))).toList();
        for (GapRecord gap : source) {
            if (gap.getStatus() != GapStatus.IN_DEVELOPMENT && gap.getStatus() != GapStatus.RESOLVED) throw new GapIssueStateException("source gap is not in development");
        }
        for (GapRecord gap : source) if (gap.getStatus() == GapStatus.IN_DEVELOPMENT) { gap.transitionTo(GapStatus.RESOLVED, evidence.operator()); gaps.save(gap); }
        LinkedHashMap<String,String> facts = new LinkedHashMap<>();
        facts.put("task", String.valueOf(taskId)); facts.put("candidate", candidate.idempotencyKey()); facts.put("gaps", String.join(",", candidate.sourceGapNos())); facts.put("evidence", evidence.reference()); facts.put("solution", evidence.summary());
        String sourceRef = "task:" + taskId + "|candidate:" + candidate.idempotencyKey()
                + "|gaps:" + String.join(",", candidate.sourceGapNos());
        memory.approveAndWrite(new StructuredMemoryCandidate(MemoryType.GAP_SOLUTION, candidate.title(), facts,
                MemoryWriteSource.GAP_RECORD, sourceRef.substring(0, Math.min(128, sourceRef.length())),
                source.stream().findFirst().map(GapRecord::getSessionId).orElse(null), false), evidence.operator());
        LinkedHashSet<Long> recipients = new LinkedHashSet<>();
        for (GapRecord gap : source) {
            if (gap.getSessionId() != null) sessions.findById(gap.getSessionId()).ifPresent(s -> addUser(recipients, s.getUserId()));
            else addUser(recipients, gap.getReporter());
        }
        for (Long recipient : recipients) notifications.saveIfAbsent(SystemNotification.create(0, recipient, SystemNotification.Category.GAP_CLOSURE, SystemNotification.Severity.INFO, "缺口已解决", evidence.summary(), SystemNotification.SourceType.GAP_CLOSURE, "task:" + taskId, java.time.Instant.now()));
    }
    private static void addUser(LinkedHashSet<Long> recipients, String v) { try { if (v != null && Long.parseLong(v) > 0) recipients.add(Long.parseLong(v)); } catch (RuntimeException ignored) { } }
}
