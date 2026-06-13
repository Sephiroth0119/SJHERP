package com.sjherp.domain.gl;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

import com.sjherp.domain.common.audit.AuditTarget;

/**
 * 会计期间档案聚合根（M4-T01）：账期开启 / 关闭管理。
 *
 * <p>账期业务键 {@link #period} = yyyyMM（与凭证号年月段、序号 scope_key 对齐）。账期<b>不走
 * 单据状态机</b>（{@link com.sjherp.domain.common.DocumentStatus}），只有 OPEN / CLOSED 两态
 * （{@link PeriodStatus}）：OPEN 期允许过账，CLOSED 期禁止过账（关账守卫在
 * {@link VoucherService#post}）。
 *
 * <h2>关账与重开（CLAUDE.md 原则 2：期间不可随意重开）</h2>
 * {@link #close} 仅 OPEN→CLOSED 一次，记录关账人/时间；重复关账拒绝。{@link #reopen} 为高敏操作
 * （CLOSED→OPEN，权限 finance:period_reopen），清空关账标记。T01 关账只改状态，月末结转留 T05。
 */
public final class AccountingPeriod implements AuditTarget {

    /** 账期键格式：yyyyMM（6 位数字） */
    private static final Pattern PERIOD_PATTERN = Pattern.compile("\\d{6}");

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 账期键 yyyyMM（租户内唯一） */
    private final String period;

    /** 冗余年份（供报表聚合） */
    private final int year;

    /** 冗余月份 1-12（供报表聚合） */
    private final int month;

    private PeriodStatus status;

    /** 关账人（CLOSED 时记录，OPEN 时为 null） */
    private String closedBy;

    /** 关账时间（CLOSED 时记录，OPEN 时为 null） */
    private Instant closedAt;

    private final String createdBy;
    private final Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;

    private AccountingPeriod(Long id, String period, int year, int month, PeriodStatus status,
                            String closedBy, Instant closedAt, String createdBy, Instant createdAt,
                            String updatedBy, Instant updatedAt) {
        this.id = id;
        this.period = period;
        this.year = year;
        this.month = month;
        this.status = status;
        this.closedBy = closedBy;
        this.closedAt = closedAt;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    /**
     * 开启新账期（初始 OPEN）。
     *
     * @param period   账期键 yyyyMM（必须匹配 6 位数字，月份 1-12）
     * @param operator 操作人
     */
    public static AccountingPeriod open(String period, String operator) {
        String normalized = validatePeriod(period);
        int year = Integer.parseInt(normalized.substring(0, 4));
        int month = Integer.parseInt(normalized.substring(4, 6));
        Objects.requireNonNull(operator, "operator 不能为空");
        Instant now = Instant.now();
        return new AccountingPeriod(null, normalized, year, month, PeriodStatus.OPEN, null, null,
                operator, now, operator, now);
    }

    /** 持久层重建工厂（不重跑业务校验，库中数据以入库时校验为准） */
    public static AccountingPeriod restore(long id, String period, int year, int month,
                                           PeriodStatus status, String closedBy, Instant closedAt,
                                           String createdBy, Instant createdAt, String updatedBy,
                                           Instant updatedAt) {
        return new AccountingPeriod(id, period, year, month, status, closedBy, closedAt, createdBy,
                createdAt, updatedBy, updatedAt);
    }

    /** 关账：OPEN→CLOSED，记录关账人/时间；已 CLOSED 再关拒绝。T01 只改状态，结转留 T05。 */
    public void close(String operator) {
        Objects.requireNonNull(operator, "operator 不能为空");
        if (status == PeriodStatus.CLOSED) {
            throw new IllegalStateException("账期[" + period + "] 已关闭，不可重复关账");
        }
        this.status = PeriodStatus.CLOSED;
        this.closedBy = operator;
        this.closedAt = Instant.now();
        touch(operator);
    }

    /**
     * 重开账期（高敏操作，CLAUDE.md 原则 2）：CLOSED→OPEN，清空关账标记。
     * 已 OPEN 再重开拒绝。
     */
    public void reopen(String operator) {
        Objects.requireNonNull(operator, "operator 不能为空");
        if (status == PeriodStatus.OPEN) {
            throw new IllegalStateException("账期[" + period + "] 当前已开启，无需重开");
        }
        this.status = PeriodStatus.OPEN;
        this.closedBy = null;
        this.closedAt = null;
        touch(operator);
    }

    /** 是否开启（过账前置校验） */
    public boolean isOpen() {
        return status == PeriodStatus.OPEN;
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("账期 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    private void touch(String operator) {
        this.updatedBy = Objects.requireNonNull(operator, "operator 不能为空");
        this.updatedAt = Instant.now();
    }

    private static String validatePeriod(String period) {
        if (period == null || period.isBlank()) {
            throw new IllegalArgumentException("账期不能为空");
        }
        String trimmed = period.strip();
        if (!PERIOD_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("账期格式必须为 yyyyMM（6 位数字）: " + trimmed);
        }
        int month = Integer.parseInt(trimmed.substring(4, 6));
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("账期月份必须在 01-12 之间: " + trimmed);
        }
        return trimmed;
    }

    // ---------------------------------------------------------------
    // 只读访问器
    // ---------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public String getPeriod() {
        return period;
    }

    public int getYear() {
        return year;
    }

    public int getMonth() {
        return month;
    }

    public PeriodStatus getStatus() {
        return status;
    }

    public String getClosedBy() {
        return closedBy;
    }

    public Instant getClosedAt() {
        return closedAt;
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

    // ---------------------------------------------------------------
    // AuditTarget（M2-T07）
    // ---------------------------------------------------------------

    @Override
    public Long auditTargetId() {
        return id;
    }

    @Override
    public String auditTargetCode() {
        return period;
    }

    @Override
    public String auditSummary() {
        return "账期=" + period + ", 状态=" + status.label()
                + ", 关账人=" + AuditTarget.text(closedBy)
                + ", 关账时间=" + AuditTarget.text(closedAt);
    }
}
