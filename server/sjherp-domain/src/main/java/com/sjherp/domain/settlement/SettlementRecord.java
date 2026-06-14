package com.sjherp.domain.settlement;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import com.sjherp.domain.common.audit.AuditTarget;
import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 核销记录（M4-T03，应收应付核销流水，<b>只追加</b>）。
 *
 * <p>一条记录 = 对某笔应收/应付施加的一次核销动作的不可变事实：类型 {@link #type}、
 * 目标子账主键 {@link #targetId}、目标来源单号 {@link #targetSourceDocNo}（追溯）、本次核销金额
 * {@link #amount}（> 0）、核销业务日 {@link #settlementDate}、收付款单号 {@link #paymentDocNo}
 * （T04 收付款单回填，T03 恒 NULL）、创建人 {@link #createdBy} 与创建时间 {@link #createdAt}。
 *
 * <p><b>财务记录只可冲销不可物理修改/删除</b>（CLAUDE.md 原则 2 / 3 可审计）：本聚合<b>不提供任何
 * 改/删方法</b>；纠错由 T07 红冲驱动（按 {@link #paymentDocNo} 反查记录反向）。子账上的
 * {@code settledAmount} 是这些只追加记录的维护型 rollup，本表才是核销的真源。
 *
 * <p>金额一律 {@link BigDecimal}（CLAUDE.md 原则 5）；时间列按 UTC 读写（{@link Instant}）。
 */
public final class SettlementRecord implements AuditTarget {

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 核销类型（应收 / 应付） */
    private final SettlementType type;

    /** 目标子账主键（accounts_receivable.id 或 accounts_payable.id） */
    private final long targetId;

    /** 目标来源单据号（AR/AP 的 source_doc_no，追溯核销冲减了哪笔挂账） */
    private final String targetSourceDocNo;

    /** 本次核销金额（> 0，2 位小数） */
    private final BigDecimal amount;

    /** 核销业务日 */
    private final LocalDate settlementDate;

    /** 收付款单号（M4-T04 收付款单回填；T03 恒 NULL，可空） */
    private final String paymentDocNo;

    /** 创建人（人工=登录名 / Agent=agent:&lt;userId&gt;，审计要求） */
    private final String createdBy;

    /** 创建时间（UTC） */
    private final Instant createdAt;

    private SettlementRecord(Long id, SettlementType type, long targetId, String targetSourceDocNo,
                            BigDecimal amount, LocalDate settlementDate, String paymentDocNo,
                            String createdBy, Instant createdAt) {
        this.id = id;
        this.type = Objects.requireNonNull(type, "核销类型不能为空");
        this.targetId = targetId;
        this.targetSourceDocNo = Objects.requireNonNull(targetSourceDocNo, "目标来源单号不能为空");
        this.amount = Objects.requireNonNull(amount, "核销金额不能为空");
        this.settlementDate = Objects.requireNonNull(settlementDate, "核销业务日不能为空");
        this.paymentDocNo = paymentDocNo;
        this.createdBy = Objects.requireNonNull(createdBy, "创建人不能为空");
        this.createdAt = Objects.requireNonNull(createdAt, "创建时间不能为空");
    }

    /**
     * 新建一条核销记录（核销引擎在子账 settle 成功后调用）。
     *
     * @param type              核销类型（应收 / 应付）
     * @param targetId          目标子账主键
     * @param targetSourceDocNo 目标来源单据号（追溯）
     * @param amount            本次核销金额（> 0，归一为 2 位）
     * @param settlementDate    核销业务日
     * @param paymentDocNo      收付款单号（T03 传 null）
     * @param createdBy         创建人
     */
    public static SettlementRecord record(SettlementType type, long targetId, String targetSourceDocNo,
                                          BigDecimal amount, LocalDate settlementDate, String paymentDocNo,
                                          String createdBy) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("核销金额必须大于 0: "
                    + (amount == null ? "null" : amount.toPlainString()));
        }
        return new SettlementRecord(null, type, targetId, targetSourceDocNo,
                amount.setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING),
                settlementDate, paymentDocNo, createdBy, Instant.now());
    }

    /**
     * 新建一条<b>反向核销记录</b>（M4-T07c 收付款单红冲在 {@code unsettle} 成功后调用）。
     *
     * <p>追加一条 {@code amount < 0} 的负额记录冲回原正向核销（只追加原则不破，settlement_record
     * 仍是核销真源）：子账 {@code settledAmount} 的 rollup 口径 = {@code Σ settlement_record.amount}
     * （含负额），故 {@code Σ核销记录 == settledAmount} 不变式继续成立——一致性规则 8/9/10 口径无需改。
     *
     * <p>负额仅本工厂允许；正向 {@link #record} 工厂仍校验 {@code amount > 0}（不变）。
     *
     * @param type              核销类型（应收 / 应付）
     * @param targetId          目标子账主键
     * @param targetSourceDocNo 目标来源单据号（追溯）
     * @param amount            本次反向核销金额（&lt; 0，归一为 2 位）
     * @param settlementDate    核销业务日
     * @param paymentDocNo      被冲销的收付款单号（反查锚点）
     * @param createdBy         创建人
     */
    public static SettlementRecord recordReversal(SettlementType type, long targetId,
                                                  String targetSourceDocNo, BigDecimal amount,
                                                  LocalDate settlementDate, String paymentDocNo,
                                                  String createdBy) {
        if (amount == null || amount.signum() >= 0) {
            throw new IllegalArgumentException("反向核销记录金额必须小于 0: "
                    + (amount == null ? "null" : amount.toPlainString()));
        }
        return new SettlementRecord(null, type, targetId, targetSourceDocNo,
                amount.setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING),
                settlementDate, paymentDocNo, createdBy, Instant.now());
    }

    /** 持久层重建工厂（不重跑业务校验） */
    public static SettlementRecord restore(long id, SettlementType type, long targetId,
                                           String targetSourceDocNo, BigDecimal amount,
                                           LocalDate settlementDate, String paymentDocNo,
                                           String createdBy, Instant createdAt) {
        return new SettlementRecord(id, type, targetId, targetSourceDocNo, amount, settlementDate,
                paymentDocNo, createdBy, createdAt);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("核销记录 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public SettlementType getType() {
        return type;
    }

    public long getTargetId() {
        return targetId;
    }

    public String getTargetSourceDocNo() {
        return targetSourceDocNo;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getSettlementDate() {
        return settlementDate;
    }

    public String getPaymentDocNo() {
        return paymentDocNo;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    // ---------------- 审计目标 ----------------

    @Override
    public Long auditTargetId() {
        return id;
    }

    @Override
    public String auditTargetCode() {
        return targetSourceDocNo;
    }

    @Override
    public String auditSummary() {
        return "类型=" + type.label() + ", 目标id=" + targetId + ", 来源单据=" + targetSourceDocNo
                + ", 核销金额=" + amount.toPlainString() + ", 核销日=" + settlementDate
                + ", 收付款单=" + AuditTarget.text(paymentDocNo);
    }
}
