package com.sjherp.domain.collection;

import java.math.BigDecimal;
import java.util.Objects;

import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 收款单行（M4-T04b）。
 *
 * <p>一行 = 把本次收款的一部分分摊到某笔应收账款（{@link #receivableId}）：分摊金额
 * {@link #allocatedAmount}（> 0，2 位）。过账时逐行经核销引擎冲减对应应收子账
 * （超额由核销引擎硬拒），故行本身只守门金额 > 0、2 位精度，对手方一致性
 * （应收客户 == 单据客户）与超额校验在编排/核销层（{@code CollectionReceiptAppService.post}）。
 */
public final class CollectionReceiptLine {

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 行号（单据内从 1 起） */
    private final int lineNo;

    /** 分摊到的应收账款主键（accounts_receivable.id） */
    private final long receivableId;

    /** 分摊金额（2 位小数，> 0；本行冲减应收的金额） */
    private final BigDecimal allocatedAmount;

    private CollectionReceiptLine(Long id, int lineNo, long receivableId, BigDecimal allocatedAmount) {
        this.id = id;
        this.lineNo = lineNo;
        this.receivableId = receivableId;
        this.allocatedAmount = Objects.requireNonNull(allocatedAmount, "分摊金额不能为空");
    }

    /**
     * 建单工厂：行号、分摊到的应收主键、分摊金额。
     *
     * @param lineNo          行号（>=1）
     * @param receivableId    分摊到的应收账款主键
     * @param allocatedAmount 分摊金额（> 0，最多 2 位小数）
     */
    public static CollectionReceiptLine create(int lineNo, long receivableId, BigDecimal allocatedAmount) {
        if (lineNo < 1) {
            throw new IllegalArgumentException("收款单行号必须 >= 1: " + lineNo);
        }
        BigDecimal amt = normalizedAmount(allocatedAmount);
        return new CollectionReceiptLine(null, lineNo, receivableId, amt);
    }

    /** 持久层重建工厂（不重跑业务校验） */
    public static CollectionReceiptLine restore(long id, int lineNo, long receivableId,
                                                BigDecimal allocatedAmount) {
        return new CollectionReceiptLine(id, lineNo, receivableId, allocatedAmount);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("收款单行 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    private static BigDecimal normalizedAmount(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("分摊金额不能为空");
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("分摊金额必须大于 0: " + amount.toPlainString());
        }
        if (amount.stripTrailingZeros().scale() > CostingStrategy.AMOUNT_SCALE) {
            throw new IllegalArgumentException("分摊金额最多 " + CostingStrategy.AMOUNT_SCALE
                    + " 位小数: " + amount.toPlainString());
        }
        return amount.setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }

    public Long getId() {
        return id;
    }

    public int getLineNo() {
        return lineNo;
    }

    public long getReceivableId() {
        return receivableId;
    }

    public BigDecimal getAllocatedAmount() {
        return allocatedAmount;
    }
}
