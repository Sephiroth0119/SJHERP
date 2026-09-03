package com.sjherp.domain.consistency;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.sjherp.domain.common.audit.AuditTarget;

/** 一次显式一致性检查的只追加运行报告聚合。 */
public final class ConsistencyCheckRun implements AuditTarget {

    public enum TriggerType { SCHEDULED, MANUAL_API, AGENT }

    public enum Status { COMPLETED, FAILED }

    public enum AnalysisStatus { SKIPPED, SUCCEEDED, FAILED }

    private Long id;
    private final long tenantId;
    private final String runNo;
    private final TriggerType triggerType;
    private final String requestedBy;
    private final Instant startedAt;
    private final Instant completedAt;
    private final Status status;
    private final boolean clean;
    private final long totalCount;
    private final long errorCount;
    private final long warnCount;
    private final long infoCount;
    private final AnalysisStatus analysisStatus;
    private final String analysisSummary;
    private final String failureType;
    private final Instant createdAt;
    private final List<ConsistencyFinding> findings;

    private ConsistencyCheckRun(Long id, long tenantId, String runNo, TriggerType triggerType,
                                String requestedBy, Instant startedAt, Instant completedAt, Status status,
                                boolean clean, long totalCount, long errorCount, long warnCount,
                                long infoCount, AnalysisStatus analysisStatus, String analysisSummary,
                                String failureType, Instant createdAt, List<ConsistencyFinding> findings) {
        this.id = id;
        this.tenantId = requireTenantId(tenantId);
        this.runNo = requireText(runNo, 32, "运行编号");
        this.triggerType = Objects.requireNonNull(triggerType, "触发类型不能为空");
        this.requestedBy = requireText(requestedBy, 64, "请求人不能为空");
        this.startedAt = Objects.requireNonNull(startedAt, "开始时间不能为空");
        this.completedAt = Objects.requireNonNull(completedAt, "完成时间不能为空");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("完成时间不能早于开始时间");
        }
        this.status = Objects.requireNonNull(status, "运行状态不能为空");
        this.clean = clean;
        this.totalCount = requireCount(totalCount, "差异总数");
        this.errorCount = requireCount(errorCount, "错误数");
        this.warnCount = requireCount(warnCount, "警告数");
        this.infoCount = requireCount(infoCount, "提示数");
        if (totalCount != errorCount + warnCount + infoCount) {
            throw new IllegalArgumentException("差异总数必须等于各严重度数量之和");
        }
        this.analysisStatus = Objects.requireNonNull(analysisStatus, "分析状态不能为空");
        this.analysisSummary = optionalText(analysisSummary, 1000, "分析摘要");
        this.failureType = optionalText(failureType, 128, "失败类型");
        this.createdAt = Objects.requireNonNull(createdAt, "创建时间不能为空");
        this.findings = List.copyOf(Objects.requireNonNull(findings, "差异明细不能为空"));
        validateFindingSequenceNumbers();
        validateState();
    }

    public static ConsistencyCheckRun completed(long tenantId, String runNo, TriggerType triggerType,
                                                String requestedBy, Instant startedAt, Instant completedAt,
                                                AnalysisStatus analysisStatus, String analysisSummary,
                                                List<ConsistencyFinding> findings) {
        List<ConsistencyFinding> copiedFindings = List.copyOf(
                Objects.requireNonNull(findings, "差异明细不能为空"));
        long errors = copiedFindings.stream().filter(f -> f.severity() == ConsistencyFinding.Severity.ERROR).count();
        long warnings = copiedFindings.stream().filter(f -> f.severity() == ConsistencyFinding.Severity.WARN).count();
        long infos = copiedFindings.stream().filter(f -> f.severity() == ConsistencyFinding.Severity.INFO).count();
        return new ConsistencyCheckRun(null, tenantId, runNo, triggerType, requestedBy,
                startedAt, completedAt, Status.COMPLETED, copiedFindings.isEmpty(), copiedFindings.size(),
                errors, warnings, infos, analysisStatus, analysisSummary, null, completedAt, copiedFindings);
    }

    public static ConsistencyCheckRun failed(long tenantId, String runNo, TriggerType triggerType,
                                             String requestedBy, Instant startedAt, Instant completedAt,
                                             String failureType) {
        return new ConsistencyCheckRun(null, tenantId, runNo, triggerType, requestedBy,
                startedAt, completedAt, Status.FAILED, false, 0, 0, 0, 0,
                AnalysisStatus.SKIPPED, null, requireText(failureType, 128, "失败类型"),
                completedAt, List.of());
    }

    /** 持久层重建聚合；列表始终防御性复制。 */
    public static ConsistencyCheckRun restore(long id, long tenantId, String runNo, TriggerType triggerType,
                                              String requestedBy, Instant startedAt, Instant completedAt,
                                              Status status, boolean clean, long totalCount, long errorCount,
                                              long warnCount, long infoCount, AnalysisStatus analysisStatus,
                                              String analysisSummary, String failureType, Instant createdAt,
                                              List<ConsistencyFinding> findings) {
        if (id < 1) {
            throw new IllegalArgumentException("运行报告 id 必须为正数");
        }
        return new ConsistencyCheckRun(id, tenantId, runNo, triggerType, requestedBy, startedAt, completedAt,
                status, clean, totalCount, errorCount, warnCount, infoCount, analysisStatus,
                analysisSummary, failureType, createdAt, findings);
    }

    /** 仓储插入后回填主键，只允许一次。 */
    public void assignId(long id) {
        if (id < 1) {
            throw new IllegalArgumentException("运行报告 id 必须为正数");
        }
        if (this.id != null) {
            throw new IllegalStateException("运行报告 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    private void validateState() {
        if (status == Status.COMPLETED && failureType != null) {
            throw new IllegalArgumentException("已完成运行不能保存失败类型");
        }
        if (status == Status.FAILED && (analysisStatus != AnalysisStatus.SKIPPED || analysisSummary != null
                || failureType == null || !findings.isEmpty() || clean || totalCount != 0
                || errorCount != 0 || warnCount != 0 || infoCount != 0)) {
            throw new IllegalArgumentException("失败运行只能保存失败类型且不能包含差异明细");
        }
    }

    private void validateFindingSequenceNumbers() {
        Set<Integer> sequenceNumbers = new HashSet<>();
        for (ConsistencyFinding finding : findings) {
            if (!sequenceNumbers.add(finding.sequenceNo())) {
                throw new IllegalArgumentException("差异序号不能重复: " + finding.sequenceNo());
            }
        }
    }

    private static long requireTenantId(long tenantId) {
        if (tenantId < 0) {
            throw new IllegalArgumentException("租户 id 不能为负数");
        }
        return tenantId;
    }

    private static long requireCount(long count, String fieldName) {
        if (count < 0) {
            throw new IllegalArgumentException(fieldName + "不能为负数");
        }
        return count;
    }

    private static String requireText(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private static String optionalText(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(value, maxLength, fieldName);
    }

    public Long id() { return id; }
    public long tenantId() { return tenantId; }
    public String runNo() { return runNo; }
    public TriggerType triggerType() { return triggerType; }
    public String requestedBy() { return requestedBy; }
    public Instant startedAt() { return startedAt; }
    public Instant completedAt() { return completedAt; }
    public Status status() { return status; }
    public boolean clean() { return clean; }
    public long totalCount() { return totalCount; }
    public long errorCount() { return errorCount; }
    public long warnCount() { return warnCount; }
    public long infoCount() { return infoCount; }
    public AnalysisStatus analysisStatus() { return analysisStatus; }
    public String analysisSummary() { return analysisSummary; }
    public String failureType() { return failureType; }
    public Instant createdAt() { return createdAt; }
    public List<ConsistencyFinding> findings() { return findings; }

    @Override
    public Long auditTargetId() { return id; }

    @Override
    public String auditTargetCode() { return runNo; }

    @Override
    public String auditSummary() {
        return "触发=" + triggerType + ", 状态=" + status + ", 总数=" + totalCount
                + ", 错误=" + errorCount + ", 警告=" + warnCount + ", 提示=" + infoCount;
    }
}
