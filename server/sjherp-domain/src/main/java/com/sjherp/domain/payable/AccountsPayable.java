package com.sjherp.domain.payable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import com.sjherp.domain.common.audit.AuditTarget;
import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 应付账款聚合根（M3-T07，路线图 §5 采购线，为 M4 应付管理铺路）。
 *
 * <p>一笔应付 = 企业因某张采购发票欠某供应商的款项：供应商 {@link #supplierId}、金额
 * {@link #amount}、来源单据号 {@link #sourceDocNo}（采购发票号）、到期日 {@link #dueDate}
 * （由供应商结算方式推算）、状态 {@link #status}（本期恒 OPEN，未核销）。
 *
 * <p><b>财务记录只可冲销、不可物理修改/删除</b>（CLAUDE.md 原则 2）：本聚合不提供任何修改金额/
 * 删除方法；纠错通过红字发票冲销驱动反向应付（M4-T07）。核销（付款冲应付）与已核销金额字段在
 * M4-T03 引入，本期 {@link #settledAmount} 恒 0、{@link #status} 恒 OPEN（留字段与 TODO）。
 *
 * <p>应付不走 BusinessDocument 状态机（它不是经办单据而是财务台账记录），由采购发票过账时产生。
 */
public final class AccountsPayable implements AuditTarget {

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 供应商 id */
    private final long supplierId;

    /** 应付金额（2 位小数，> 0；本期不可改，纠错走红字冲销 M4-T07） */
    private final BigDecimal amount;

    /** 来源单据号（采购发票号 PINV-年月-序号，可追溯到收货与采购订单） */
    private final String sourceDocNo;

    /** 到期日（由供应商结算方式推算：现结=发票日、月结=次月固定日、预付=发票日） */
    private final LocalDate dueDate;

    /** 状态（本期恒 OPEN，未核销） */
    private PayableStatus status;

    /** 已核销金额（M4-T03 核销时累加，本期恒 0；留字段） */
    private BigDecimal settledAmount;

    private final String createdBy;
    private final Instant createdAt;

    private AccountsPayable(Long id, long supplierId, BigDecimal amount, String sourceDocNo,
                           LocalDate dueDate, PayableStatus status, BigDecimal settledAmount,
                           String createdBy, Instant createdAt) {
        this.id = id;
        this.supplierId = supplierId;
        this.amount = Objects.requireNonNull(amount, "应付金额不能为空");
        this.sourceDocNo = Objects.requireNonNull(sourceDocNo, "来源单据号不能为空");
        this.dueDate = Objects.requireNonNull(dueDate, "到期日不能为空");
        this.status = Objects.requireNonNull(status, "应付状态不能为空");
        this.settledAmount = Objects.requireNonNull(settledAmount, "已核销金额不能为空");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy 不能为空");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不能为空");
    }

    /**
     * 新生成一笔未核销应付（采购发票过账时产生）。
     *
     * @param supplierId  供应商 id
     * @param amount      应付金额（> 0，2 位小数）
     * @param sourceDocNo 来源采购发票号
     * @param dueDate     到期日（由供应商结算方式推算）
     * @param operator    操作人
     */
    public static AccountsPayable open(long supplierId, BigDecimal amount, String sourceDocNo,
                                       LocalDate dueDate, String operator) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("应付金额必须大于 0: "
                    + (amount == null ? "null" : amount.toPlainString()));
        }
        BigDecimal normalized = amount.setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
        return new AccountsPayable(null, supplierId, normalized, sourceDocNo, dueDate,
                PayableStatus.OPEN, BigDecimal.ZERO, operator, Instant.now());
    }

    /** 持久层重建工厂（不重跑业务校验） */
    public static AccountsPayable restore(long id, long supplierId, BigDecimal amount, String sourceDocNo,
                                          LocalDate dueDate, PayableStatus status, BigDecimal settledAmount,
                                          String createdBy, Instant createdAt) {
        return new AccountsPayable(id, supplierId, amount, sourceDocNo, dueDate, status,
                settledAmount, createdBy, createdAt);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("应付账款 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    /** 未核销余额（= 金额 − 已核销金额，本期恒 = 金额） */
    public BigDecimal outstandingAmount() {
        return amount.subtract(settledAmount);
    }

    public Long getId() {
        return id;
    }

    public long getSupplierId() {
        return supplierId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getSourceDocNo() {
        return sourceDocNo;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public PayableStatus getStatus() {
        return status;
    }

    public BigDecimal getSettledAmount() {
        return settledAmount;
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
        return sourceDocNo;
    }

    @Override
    public String auditSummary() {
        return "供应商=" + supplierId + ", 金额=" + amount.toPlainString()
                + ", 来源单据=" + sourceDocNo + ", 到期日=" + dueDate
                + ", 状态=" + status.label();
    }
}
