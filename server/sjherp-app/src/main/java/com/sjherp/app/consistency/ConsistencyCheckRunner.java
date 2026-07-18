package com.sjherp.app.consistency;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.consistency.ConsistencyCheckRun;
import com.sjherp.domain.consistency.ConsistencyCheckRun.AnalysisStatus;
import com.sjherp.domain.consistency.ConsistencyCheckRun.TriggerType;
import com.sjherp.domain.consistency.ConsistencyFinding;

/** 统一编排显式一致性检查；规则读取不处于报告写事务中。 */
@Service
public class ConsistencyCheckRunner {

    private static final Logger log = LoggerFactory.getLogger(ConsistencyCheckRunner.class);
    private static final long TENANT_ID = 0L;
    private static final DocumentNumberRule RUN_NUMBER_RULE = DocumentNumberRule.of("CHK");
    private static final String SCHEDULED_OPERATOR = "system:consistency-scheduler";
    private static final int ANALYSIS_SUMMARY_MAX_LENGTH = 1000;

    private final ConsistencyRuleRegistry registry;
    private final DocumentNumberGenerator numberGenerator;
    private final ConsistencyRunPersistenceService persistence;
    private final Clock clock;

    @Autowired
    public ConsistencyCheckRunner(ConsistencyRuleRegistry registry,
                                  DocumentNumberGenerator numberGenerator,
                                  ConsistencyRunPersistenceService persistence) {
        this(registry, numberGenerator, persistence, Clock.systemUTC());
    }

    ConsistencyCheckRunner(ConsistencyRuleRegistry registry,
                           DocumentNumberGenerator numberGenerator,
                           ConsistencyRunPersistenceService persistence,
                           Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        this.numberGenerator = Objects.requireNonNull(numberGenerator, "numberGenerator 不能为空");
        this.persistence = Objects.requireNonNull(persistence, "persistence 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    @Audited(action = "consistency.run", targetType = "consistency_report")
    public ConsistencyCheckRun runManual(String operator) {
        return run(TriggerType.MANUAL_API, operator);
    }

    public ConsistencyCheckRun runAgent(String userId) {
        return run(TriggerType.AGENT, "agent:" + requireText(userId, "userId"));
    }

    public ConsistencyCheckRun runScheduled() {
        return run(TriggerType.SCHEDULED, SCHEDULED_OPERATOR);
    }

    private ConsistencyCheckRun run(TriggerType triggerType, String requestedBy) {
        String checkedRequestedBy = requireText(requestedBy, "requestedBy");
        Instant startedAt = Instant.now(clock);
        String runNo = numberGenerator.generate(RUN_NUMBER_RULE);
        ConsistencyRule.Context context = new ConsistencyRule.Context(
                TENANT_ID, runNo, triggerType, checkedRequestedBy);
        List<ConsistencyFinding> findings = new ArrayList<>();

        try {
            for (ConsistencyRule rule : registry.sqlRules()) {
                ConsistencyRule.Result result = Objects.requireNonNull(
                        rule.evaluate(context), "SQL rule result 不能为空: " + rule.code());
                for (ConsistencyBreak breakItem : result.breaks()) {
                    findings.add(toFinding(findings.size() + 1, rule.code(), breakItem));
                }
            }
        } catch (RuntimeException deterministicFailure) {
            ConsistencyCheckRun failed = persistFailedRunWithoutReplacingOriginal(
                    runNo, triggerType, checkedRequestedBy,
                    startedAt, deterministicFailure);
            if (triggerType == TriggerType.SCHEDULED) {
                return failed;
            }
            throw deterministicFailure;
        }

        AnalysisOutcome analysis = runLlmRules(context);
        ConsistencyCheckRun completed = ConsistencyCheckRun.completed(
                TENANT_ID, runNo, triggerType, checkedRequestedBy, startedAt, Instant.now(clock),
                analysis.status(), analysis.summary(), findings);
        persistence.persist(completed);
        return completed;
    }

    private AnalysisOutcome runLlmRules(ConsistencyRule.Context context) {
        if (registry.llmRules().isEmpty()) {
            return new AnalysisOutcome(AnalysisStatus.SKIPPED, null);
        }
        boolean failed = false;
        List<String> summaries = new ArrayList<>();
        for (ConsistencyRule rule : registry.llmRules()) {
            try {
                ConsistencyRule.Result result = Objects.requireNonNull(
                        rule.evaluate(context), "LLM rule result 不能为空: " + rule.code());
                if (result.analysisSummary() != null && !result.analysisSummary().isBlank()) {
                    summaries.add(result.analysisSummary().strip());
                }
            } catch (RuntimeException analysisFailure) {
                failed = true;
                log.warn("一致性分析规则失败，已 fail-open（ruleCode={}, failureType={}）",
                        rule.code(), analysisFailure.getClass().getSimpleName());
            }
        }
        return new AnalysisOutcome(failed ? AnalysisStatus.FAILED : AnalysisStatus.SUCCEEDED,
                aggregateAnalysisSummaries(summaries));
    }

    private static String aggregateAnalysisSummaries(List<String> summaries) {
        StringBuilder combined = new StringBuilder(ANALYSIS_SUMMARY_MAX_LENGTH);
        for (String summary : summaries) {
            if (combined.length() >= ANALYSIS_SUMMARY_MAX_LENGTH) {
                break;
            }
            if (!combined.isEmpty()) {
                combined.append('\n');
            }
            int remaining = ANALYSIS_SUMMARY_MAX_LENGTH - combined.length();
            combined.append(summary, 0, Math.min(summary.length(), remaining));
        }
        return combined.isEmpty() ? null : combined.toString();
    }

    private ConsistencyCheckRun persistFailedRunWithoutReplacingOriginal(
            String runNo, TriggerType triggerType, String requestedBy, Instant startedAt,
            RuntimeException originalFailure) {
        ConsistencyCheckRun failed = ConsistencyCheckRun.failed(
                TENANT_ID, runNo, triggerType, requestedBy, startedAt, Instant.now(clock),
                originalFailure.getClass().getSimpleName());
        try {
            persistence.persist(failed);
        } catch (RuntimeException persistenceFailure) {
            if (triggerType == TriggerType.SCHEDULED) {
                log.error("一致性定时巡检失败摘要持久化失败"
                                + "（runNo={}，总数=0，ERROR=0，WARN=0，INFO=0）",
                        runNo);
            } else {
                log.error("一致性失败摘要持久化失败，保留原始确定性异常"
                                + "（deterministicFailureType={}, persistenceFailureType={}）",
                        originalFailure.getClass().getSimpleName(),
                        persistenceFailure.getClass().getSimpleName());
            }
        }
        return failed;
    }

    private static ConsistencyFinding toFinding(int sequenceNo, String ruleCode,
                                                ConsistencyBreak breakItem) {
        Objects.requireNonNull(breakItem, "break 不能为空");
        return new ConsistencyFinding(sequenceNo, ruleCode, breakItem.checkType().code(),
                breakItem.key(), decimal(breakItem.expected()), decimal(breakItem.actual()),
                ConsistencyFinding.Severity.valueOf(breakItem.severity().name()), breakItem.message());
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value.strip();
    }

    private record AnalysisOutcome(AnalysisStatus status, String summary) {}
}
