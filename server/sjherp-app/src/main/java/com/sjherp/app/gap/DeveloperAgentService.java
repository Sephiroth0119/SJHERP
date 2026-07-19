package com.sjherp.app.gap;

import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.gap.*;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeveloperAgentService {
    private final GapIssueCandidateRepository candidates;
    private final DeveloperAgentTaskRepository tasks;
    private final GapRecordRepository gaps;
    private final GapRecordService gapService;
    private final DeveloperAgentRunner runner;
    private final WorkspacePolicy workspacePolicy;
    private final DeveloperAgentFailureService failureService;

    @Autowired
    DeveloperAgentService(GapIssueCandidateRepository candidates,
                                 DeveloperAgentTaskRepository tasks,
                                 GapRecordRepository gaps,
                                 GapRecordService gapService,
                                 DeveloperAgentRunner runner,
                                 WorkspacePolicy workspacePolicy,
                                 DeveloperAgentFailureService failureService) {
        this.candidates = candidates;
        this.tasks = tasks;
        this.gaps = gaps;
        this.gapService = gapService;
        this.runner = runner;
        this.workspacePolicy = workspacePolicy;
        this.failureService = failureService;
    }

    public DeveloperAgentService(GapIssueCandidateRepository candidates,
                                 DeveloperAgentTaskRepository tasks,
                                 GapRecordRepository gaps,
                                 GapRecordService gapService,
                                 DeveloperAgentRunner runner,
                                 WorkspacePolicy workspacePolicy) {
        this(candidates, tasks, gaps, gapService, runner, workspacePolicy,
                new DeveloperAgentFailureService(tasks));
    }

    @Transactional
    @Audited(action = "developer.task.create", targetType = "developer_task")
    public DeveloperAgentTask start(long candidateId, String operator) {
        GapIssueCandidate candidate = candidates.findById(candidateId)
                .orElseThrow(() -> new GapIssueNotFoundException(candidateId));
        if (candidate.status() != GapIssueStatus.SENT || candidate.issueNumber() == null) {
            throw new GapIssueStateException("only SENT Issue candidates may start development");
        }
        String branch = "codex/dev/" + candidate.idempotencyKey();
        Path workspace = workspacePolicy.validate(
                branch, Path.of("developer-agent-workspaces", candidate.idempotencyKey()));
        List<GapRecord> sourceGaps = candidate.sourceGapNos().stream()
                .map(gapNo -> gaps.findByGapNo(gapNo)
                        .orElseThrow(() -> new GapRecordNotFoundException(gapNo)))
                .toList();
        if (sourceGaps.stream().anyMatch(gap -> gap.getStatus() != GapStatus.TRIAGED
                && gap.getStatus() != GapStatus.IN_DEVELOPMENT)) {
            throw new GapIssueStateException("developer task source gap is not triaged");
        }
        DeveloperAgentTask task = new DeveloperAgentTask(
                0, candidateId, "developer:" + candidate.idempotencyKey(),
                DeveloperAgentTaskStatus.QUEUED, branch, workspace.toString(), runner.kind(),
                null, 0, List.of(), false, false, false, null, false,
                null, null, null);
        DeveloperAgentTask saved = tasks.createIfAbsent(task, operator);
        for (GapRecord gap : sourceGaps) {
            String gapNo = gap.getGapNo();
            if (gap.getStatus() == GapStatus.TRIAGED) {
                gapService.transitionStatusByGapNo(gapNo, GapStatus.IN_DEVELOPMENT, operator);
            }
        }
        return saved;
    }

    public DeveloperAgentTask get(long id) {
        return tasks.findById(id).orElseThrow(() -> new DeveloperAgentTaskNotFoundException(id));
    }

    @Audited(action = "developer.task.run", targetType = "developer_task")
    public DeveloperAgentTask run(long id, String operator) {
        if (!runner.available()) {
            throw new GapIssueDisabledException("developer agent execution is disabled");
        }
        DeveloperAgentTask queued = get(id);
        candidates.findById(queued.candidateId())
                .orElseThrow(() -> new GapIssueNotFoundException(queued.candidateId()));
        String lease = tasks.claim(id, Instant.now())
                .orElseThrow(() -> new GapIssueStateException("task is already leased or not retryable"));
        try {
            DeveloperAgentTask claimed = get(id);
            GapIssueCandidate candidate = candidates.findById(claimed.candidateId())
                    .orElseThrow(() -> new GapIssueNotFoundException(claimed.candidateId()));
            DeveloperAgentRunner.Result result = runner.run(
                    new DeveloperAgentRunner.RunRequest(claimed, candidate));
            if (result.generatedArtifacts().isEmpty()
                    || result.generatedArtifacts().stream().anyMatch(String::isBlank)
                    || !result.targetedTestsGreen()) {
            fail(id, DeveloperAgentTaskStatus.RUNNING, lease, "QUALITY_GATE", "missing artifacts or targeted tests", result, operator);
                return get(id);
            }
            transition(id, DeveloperAgentTaskStatus.RUNNING, DeveloperAgentTaskStatus.TESTING,
                    lease, result, false);
            if (!result.fullTestsGreen() || !result.ciGreen()
                    || result.ciEvidence() == null || result.ciEvidence().isBlank()) {
                fail(id, DeveloperAgentTaskStatus.TESTING, lease, "QUALITY_GATE", "full tests or CI evidence missing", result, operator);
                return get(id);
            }
            transition(id, DeveloperAgentTaskStatus.TESTING,
                    DeveloperAgentTaskStatus.AWAITING_REVIEW, lease, result, true);
            return get(id);
        } catch (RuntimeException ex) {
            try {
                DeveloperAgentTask current = get(id);
                failureService.fail(id, current.status(), lease, ex.getClass().getSimpleName(),
                        truncate(ex.getMessage(), 500), current.generatedArtifacts(), current.targetedTestsGreen(),
                        current.fullTestsGreen(), current.ciGreen(), current.ciEvidence(), current.runnerOutputSummary(), operator);
            } catch (RuntimeException failure) {
                ex.addSuppressed(failure);
            }
            if (ex instanceof IllegalStateException) {
                throw new DeveloperAgentTaskStateException(ex.getMessage());
            }
            throw ex;
        }
    }

    private void transition(long id, DeveloperAgentTaskStatus from, DeveloperAgentTaskStatus to,
                            String lease, DeveloperAgentRunner.Result result, boolean ci) {
        try {
            tasks.transition(id, from, to, lease, result.generatedArtifacts(), true,
                    to == DeveloperAgentTaskStatus.AWAITING_REVIEW,
                    ci, to == DeveloperAgentTaskStatus.AWAITING_REVIEW
                            ? result.ciEvidence() : null, result.outputSummary());
        } catch (IllegalStateException ex) {
            throw new DeveloperAgentTaskStateException(ex.getMessage());
        }
    }

    private void fail(long id, DeveloperAgentTaskStatus expected, String lease, String type,
                      String summary, DeveloperAgentRunner.Result result, String operator) {
        try {
            failureService.fail(id, expected, lease, type, summary, result.generatedArtifacts(),
                    result.targetedTestsGreen(), result.fullTestsGreen(), result.ciGreen(),
                    result.ciEvidence(), result.outputSummary(), operator);
        } catch (IllegalStateException ex) {
            throw new DeveloperAgentTaskStateException(ex.getMessage());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return "unspecified";
        return value.length() <= max ? value : value.substring(0, max);
    }

    @Transactional
    @Audited(action = "developer.task.approve", targetType = "developer_task")
    public DeveloperAgentTask approve(long id, String operator) {
        try {
            tasks.approve(id, operator);
        } catch (IllegalStateException ex) {
            throw new DeveloperAgentTaskStateException(ex.getMessage());
        }
        return get(id);
    }

    @Transactional
    @Audited(action = "developer.task.cancel", targetType = "developer_task")
    public DeveloperAgentTask cancel(long id, String operator) {
        try {
            tasks.cancel(id, operator);
        } catch (IllegalStateException ex) {
            throw new DeveloperAgentTaskStateException(ex.getMessage());
        }
        return get(id);
    }

    @Transactional
    @Audited(action = "developer.task.reclaim", targetType = "developer_task")
    public int reclaimExpired(Duration lease, String operator) {
        return tasks.reclaimExpired(Instant.now().minus(lease));
    }
}
