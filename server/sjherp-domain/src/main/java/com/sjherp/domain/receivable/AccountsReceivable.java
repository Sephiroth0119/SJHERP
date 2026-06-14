package com.sjherp.domain.receivable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import com.sjherp.domain.common.OverSettlementException;
import com.sjherp.domain.common.audit.AuditTarget;
import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 应收账款（M3-T10，路线图 §5 销售线 / §财务）。
 *
 * <p>销售发票过账时产生一条应收记录：某客户因某来源单据（销售发票）欠企业的钱。
 * 不走 {@link com.sjherp.domain.common.BusinessDocument BusinessDocument} 状态机
 * （它不是流转型业务单据，而是财务台账记录），状态由 {@link ReceivableStatus} 表示。
 *
 * <h2>核销（M4-T03 预留）</h2>
 * v1.0 开票即 OPEN（未核销），收款核销在 M4-T03 落地：届时 {@link #settledAmount 已核销金额}
 * 随收款累加，状态推进 OPEN → PARTIAL → SETTLED。本聚合先把字段与 {@link #openAmount 未核销余额}
 * 派生方法预留好，核销动作（settle）留 TODO。
 *
 * <p>金额一律 {@link BigDecimal}（CLAUDE.md 原则 5）；财务记录只可冲销不可物理修改/删除
 * （原则 2，冲销 M4 统一做）。
 */
public final class AccountsReceivable implements AuditTarget {

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 客户 id */
    private final long customerId;

    /** 应收总金额（>=0，2 位；= 销售发票金额） */
    private final BigDecimal amount;

    /** 已核销金额（2 位，v1.0 恒 0，M4-T03 收款核销累加） */
    private BigDecimal settledAmount;

    /** 来源单据号（销售发票号 SINV-xxx，可追溯应收的成因） */
    private final String sourceDocNo;

    /** 到期日（账期，可空——无账期即即期应收） */
    private final LocalDate dueDate;

    /** 状态（v1.0 仅 OPEN） */
    private ReceivableStatus status;

    /** 创建人（审计要求） */
    private final String createdBy;

    private AccountsReceivable(Long id, long customerId, BigDecimal amount, BigDecimal settledAmount,
                              String sourceDocNo, LocalDate dueDate, ReceivableStatus status,
                              String createdBy) {
        this.id = id;
        this.customerId = customerId;
        this.amount = Objects.requireNonNull(amount, "应收金额不能为空");
        this.settledAmount = Objects.requireNonNull(settledAmount, "已核销金额不能为空");
        this.sourceDocNo = Objects.requireNonNull(sourceDocNo, "来源单据号不能为空");
        this.dueDate = dueDate;
        this.status = Objects.requireNonNull(status, "应收状态不能为空");
        this.createdBy = Objects.requireNonNull(createdBy, "创建人不能为空");
    }

    /**
     * 销售发票过账时新建应收（OPEN，已核销 0）。
     *
     * @param customerId  客户 id
     * @param amount      应收金额（>=0，= 发票金额）
     * @param sourceDocNo 来源销售发票号
     * @param dueDate     到期日（可空）
     * @param createdBy   创建人
     */
    public static AccountsReceivable open(long customerId, BigDecimal amount, String sourceDocNo,
                                          LocalDate dueDate, String createdBy) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("应收金额不能为负: "
                    + (amount == null ? "null" : amount.toPlainString()));
        }
        return new AccountsReceivable(null, customerId,
                amount.setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING),
                BigDecimal.ZERO.setScale(CostingStrategy.AMOUNT_SCALE), sourceDocNo, dueDate,
                ReceivableStatus.OPEN, createdBy);
    }

    /** 持久层重建工厂（不重跑业务校验） */
    public static AccountsReceivable restore(long id, long customerId, BigDecimal amount,
                                             BigDecimal settledAmount, String sourceDocNo,
                                             LocalDate dueDate, ReceivableStatus status, String createdBy) {
        return new AccountsReceivable(id, customerId, amount, settledAmount, sourceDocNo, dueDate,
                status, createdBy);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("应收 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    /** 未核销余额 = 应收金额 − 已核销金额（M4-T03 收款核销用） */
    public BigDecimal openAmount() {
        return amount.subtract(settledAmount);
    }

    /**
     * 核销（收款冲应收，M4-T03）：把本次核销金额累加到 {@link #settledAmount} 并推进状态。
     *
     * <p>语义（设计真源 §1.2）：
     * <ul>
     *   <li>{@code amount <= 0} → {@link IllegalArgumentException}；先 {@code setScale(AMOUNT_SCALE, ROUNDING)} 归一；</li>
     *   <li>{@code newSettled = settledAmount + amount}；若 {@code newSettled > this.amount}
     *       抛 {@link OverSettlementException}（绝不允许超额核销）；</li>
     *   <li>落 {@code settledAmount = newSettled}；状态重算：{@code == amount → SETTLED}，否则 {@code PARTIAL}。</li>
     * </ul>
     *
     * <p>只动 {@link #settledAmount} / {@link #status}（其余 final 字段不变）：原始 {@link #amount} 永不变
     * （CLAUDE.md 原则 2）。{@code settledAmount} 是只追加核销记录的维护型 rollup，更新它不破坏不变量。
     *
     * @param amount 本次核销金额（> 0，2 位精度）
     */
    public void settle(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("核销金额必须大于 0: "
                    + (amount == null ? "null" : amount.toPlainString()));
        }
        BigDecimal delta = amount.setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
        BigDecimal newSettled = settledAmount.add(delta);
        if (newSettled.compareTo(this.amount) > 0) {
            throw new OverSettlementException(delta, settledAmount, this.amount);
        }
        this.settledAmount = newSettled;
        this.status = newSettled.compareTo(this.amount) == 0
                ? ReceivableStatus.SETTLED : ReceivableStatus.PARTIAL;
    }

    /**
     * 是否可冲销（M4-T07b 业务单据红冲）：仅<b>未发生任何核销且仍 OPEN</b> 的应收可整笔冲回。
     *
     * <p>设计真源 §1.7/§2 共享基元 2：已（部分）核销的发票须先冲销对应收款单（T07c），
     * 不做带核销的递归级联——保数据模型不破碎，复杂级联留显式前置拒绝。
     */
    public boolean canBeReversed() {
        return settledAmount.signum() == 0 && status == ReceivableStatus.OPEN;
    }

    /**
     * 标记冲销（M4-T07b 销售发票红冲时由 AppService 同事务调用）：OPEN → REVERSED。
     *
     * <p>不满足 {@link #canBeReversed()}（已核销 / 已非 OPEN）抛 {@link IllegalStateException}
     * （宁可拒绝，不可破坏模型）。只动 {@link #status}，原始 {@link #amount} 永不变（原则 2）。
     *
     * @param operator 操作人（保留入参语义统一，状态变更审计由 AppService @Audited 覆盖）
     */
    public void markReversed(String operator) {
        if (!canBeReversed()) {
            throw new IllegalStateException("应收[" + sourceDocNo + "] 当前状态 " + status.label()
                    + "、已核销 " + settledAmount.toPlainString()
                    + " 不可冲销（仅未核销且未冲销的应收可整笔冲回，已核销请先冲对应收款单）");
        }
        this.status = ReceivableStatus.REVERSED;
    }

    public Long getId() {
        return id;
    }

    public long getCustomerId() {
        return customerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getSettledAmount() {
        return settledAmount;
    }

    public String getSourceDocNo() {
        return sourceDocNo;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public ReceivableStatus getStatus() {
        return status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    // ---------------------------------------------------------------
    // AuditTarget
    // ---------------------------------------------------------------

    @Override
    public Long auditTargetId() {
        return id;
    }

    @Override
    public String auditTargetCode() {
        return sourceDocNo;
    }

    @Override
    public String auditSummary() {
        return "客户=" + customerId + ", 金额=" + amount.toPlainString() + ", 状态=" + status.label()
                + ", 来源单据=" + sourceDocNo + ", 到期日=" + AuditTarget.text(
                        dueDate == null ? null : dueDate.toString());
    }
}
