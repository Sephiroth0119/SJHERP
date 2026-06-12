package com.sjherp.domain.gap;

import java.time.Instant;
import java.util.Objects;

import com.sjherp.domain.common.audit.AuditTarget;

/**
 * 流程缺口记录聚合根（M1-T04，自进化闭环第一环）。
 *
 * <p>用户向 Agent 提出系统当前做不到的需求时，Agent 通过专门工具把缺口
 * **结构化落库**（场景 / 期望 / 缺失能力），而不是自由发挥绕过领域模型硬做
 * （CLAUDE.md「流程缺口通道」）。后续 M6-T08 据此聚类生成开发 Issue，
 * M6-T10 在缺口解决后回写通知原会话用户。
 *
 * <p>缺口不是单据：没有冲销语义，走 {@link GapStatus} 的简单状态流转；
 * 记录不可物理删除（误报走 REJECTED 终态，过程可追溯）。
 */
public final class GapRecord implements AuditTarget {

    private static final int TITLE_MAX_LENGTH = 200;
    private static final int SCENARIO_MAX_LENGTH = 2000;
    private static final int EXPECTED_MAX_LENGTH = 2000;
    private static final int CAPABILITY_MAX_LENGTH = 1000;
    private static final int SESSION_ID_MAX_LENGTH = 64;
    private static final int REPORTER_MAX_LENGTH = 64;

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 缺口编号（GAP-年月-序号，如 GAP-202606-0001，复用单据编号机制生成） */
    private final String gapNo;

    /** 来源会话 id（Agent 落库时携带，M6-T10 回写通知的依据）；可空（开发侧手工补录） */
    private final String sessionId;

    /** 缺口一句话标题 */
    private final String title;

    /** 用户场景（原文或 Agent 复述） */
    private final String scenario;

    /** 用户期望系统做到什么 */
    private final String expectedBehavior;

    /** Agent 判断当前系统缺失的能力 */
    private final String missingCapability;

    /** 所属业务模块 */
    private final BusinessModule businessModule;

    /** 严重度 */
    private final GapSeverity severity;

    /** 状态（简单流转，见 {@link GapStatus}） */
    private GapStatus status;

    /** 提出人（userId 占位；M2-T05 登录落地后为真实用户） */
    private final String reporter;

    private final String createdBy;
    private final Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;

    /** 新建缺口记录，初始状态 NEW（id 由仓储落库后回填） */
    public GapRecord(String gapNo, String sessionId, String title, String scenario,
                     String expectedBehavior, String missingCapability,
                     BusinessModule businessModule, GapSeverity severity,
                     String reporter, String operator) {
        this.gapNo = requireText(gapNo, 32, "缺口编号");
        this.sessionId = optionalText(sessionId, SESSION_ID_MAX_LENGTH, "来源会话 id");
        this.title = requireText(title, TITLE_MAX_LENGTH, "缺口标题");
        this.scenario = requireText(scenario, SCENARIO_MAX_LENGTH, "用户场景");
        this.expectedBehavior = requireText(expectedBehavior, EXPECTED_MAX_LENGTH, "期望行为");
        this.missingCapability = requireText(missingCapability, CAPABILITY_MAX_LENGTH, "缺失能力");
        this.businessModule = Objects.requireNonNull(businessModule, "业务模块不能为空");
        this.severity = Objects.requireNonNull(severity, "严重度不能为空");
        this.reporter = requireText(reporter, REPORTER_MAX_LENGTH, "提出人");
        this.status = GapStatus.NEW;
        this.createdBy = Objects.requireNonNull(operator, "operator 不能为空");
        this.createdAt = Instant.now();
        this.updatedBy = operator;
        this.updatedAt = this.createdAt;
    }

    private GapRecord(Long id, String gapNo, String sessionId, String title, String scenario,
                      String expectedBehavior, String missingCapability, BusinessModule businessModule,
                      GapSeverity severity, GapStatus status, String reporter,
                      String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
        this.id = id;
        this.gapNo = gapNo;
        this.sessionId = sessionId;
        this.title = title;
        this.scenario = scenario;
        this.expectedBehavior = expectedBehavior;
        this.missingCapability = missingCapability;
        this.businessModule = businessModule;
        this.severity = severity;
        this.status = status;
        this.reporter = reporter;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    /** 持久层重建工厂（不重跑业务校验，库中数据以入库时校验为准） */
    public static GapRecord restore(long id, String gapNo, String sessionId, String title,
                                    String scenario, String expectedBehavior, String missingCapability,
                                    BusinessModule businessModule, GapSeverity severity, GapStatus status,
                                    String reporter, String createdBy, Instant createdAt,
                                    String updatedBy, Instant updatedAt) {
        return new GapRecord(id, gapNo, sessionId, title, scenario, expectedBehavior, missingCapability,
                businessModule, severity, status, reporter, createdBy, createdAt, updatedBy, updatedAt);
    }

    /**
     * 状态流转（合法性按 {@link GapStatus} 流转表检查，非法流转直接拒绝——
     * 宁可拒绝，不可破坏模型）。
     */
    public void transitionTo(GapStatus target, String operator) {
        Objects.requireNonNull(target, "目标状态不能为空");
        if (!status.canTransitionTo(target)) {
            throw new IllegalArgumentException(
                    "缺口[" + gapNo + "] 不允许从 " + status + " 流转到 " + target
                            + "（允许：" + status.allowedTargets() + "）");
        }
        this.status = target;
        touch(operator);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("缺口记录 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    private void touch(String operator) {
        this.updatedBy = Objects.requireNonNull(operator, "operator 不能为空");
        this.updatedAt = Instant.now();
    }

    // ---------------------------------------------------------------
    // 校验
    // ---------------------------------------------------------------

    private static String requireText(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        String trimmed = value.strip();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "不能超过 " + maxLength + " 个字符");
        }
        return trimmed;
    }

    /** 可空字段：空白视为 null，超长拒绝 */
    private static String optionalText(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "不能超过 " + maxLength + " 个字符");
        }
        return trimmed;
    }

    // ---------------------------------------------------------------
    // 只读访问器
    // ---------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public String getGapNo() {
        return gapNo;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getTitle() {
        return title;
    }

    public String getScenario() {
        return scenario;
    }

    public String getExpectedBehavior() {
        return expectedBehavior;
    }

    public String getMissingCapability() {
        return missingCapability;
    }

    public BusinessModule getBusinessModule() {
        return businessModule;
    }

    public GapSeverity getSeverity() {
        return severity;
    }

    public GapStatus getStatus() {
        return status;
    }

    public String getReporter() {
        return reporter;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // ---------------- 审计目标（M2-T07） ----------------

    @Override
    public Long auditTargetId() {
        return id;
    }

    @Override
    public String auditTargetCode() {
        return gapNo;
    }

    @Override
    public String auditSummary() {
        return "编号=" + AuditTarget.text(gapNo) + ", 标题=" + AuditTarget.text(title)
                + ", 模块=" + businessModule + ", 严重度=" + severity
                + ", 状态=" + status + ", 提出人=" + AuditTarget.text(reporter);
    }
}
