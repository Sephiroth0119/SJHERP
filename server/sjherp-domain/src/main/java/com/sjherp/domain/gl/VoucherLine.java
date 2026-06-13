package com.sjherp.domain.gl;

import java.math.BigDecimal;
import java.util.Objects;

import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 凭证行（值对象，M4-T01）：一条借或贷的分录。
 *
 * <p>行级不变式（{@link #create} 工厂强制，CLAUDE.md 原则 2/5）：
 * <ul>
 *   <li>{@link #debit}、{@link #credit} 均非负、最多 2 位小数（金额 DECIMAL(18,2)）；</li>
 *   <li><b>恰好一方 &gt; 0、另一方 = 0</b>——一条分录只能记借或记贷，不能两边都有、也不能两边都空。</li>
 * </ul>
 * 违反抛 {@link IllegalArgumentException}。挂账科目（{@link #accountCode}）须为末级且启用，由
 * {@link VoucherService#create} 校验。
 */
public final class VoucherLine {

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 行号（单据内从 1 起，单据内唯一） */
    private final int lineNo;

    /** 挂账科目编码（须末级且启用，由 VoucherService 校验） */
    private final String accountCode;

    /** 借方金额（非负，2 位小数；与 credit 恰好一方 > 0） */
    private final BigDecimal debit;

    /** 贷方金额（非负，2 位小数；与 debit 恰好一方 > 0） */
    private final BigDecimal credit;

    /** 行摘要（可空） */
    private final String summary;

    private VoucherLine(Long id, int lineNo, String accountCode, BigDecimal debit, BigDecimal credit,
                        String summary) {
        this.id = id;
        this.lineNo = lineNo;
        this.accountCode = accountCode;
        this.debit = debit;
        this.credit = credit;
        this.summary = summary;
    }

    /**
     * 建行工厂：强制行级不变式（非负、2 位、恰好借或贷一方 &gt; 0）。
     *
     * @param lineNo      行号（≥1）
     * @param accountCode 挂账科目编码（非空）
     * @param debit       借方金额（非负，最多 2 位小数；可为 null 视作 0）
     * @param credit      贷方金额（非负，最多 2 位小数；可为 null 视作 0）
     * @param summary     行摘要（可空）
     */
    public static VoucherLine create(int lineNo, String accountCode, BigDecimal debit, BigDecimal credit,
                                     String summary) {
        if (lineNo < 1) {
            throw new IllegalArgumentException("凭证行号必须 >= 1: " + lineNo);
        }
        if (accountCode == null || accountCode.isBlank()) {
            throw new IllegalArgumentException("凭证行科目编码不能为空");
        }
        BigDecimal normalizedDebit = normalizeAmount(debit, "借方金额");
        BigDecimal normalizedCredit = normalizeAmount(credit, "贷方金额");
        boolean debitSide = normalizedDebit.signum() > 0;
        boolean creditSide = normalizedCredit.signum() > 0;
        if (debitSide == creditSide) {
            // 两边都 > 0，或两边都 = 0，均非法
            throw new IllegalArgumentException("凭证行[" + lineNo
                    + "] 必须恰好借或贷一方金额大于 0（借=" + normalizedDebit.toPlainString()
                    + ", 贷=" + normalizedCredit.toPlainString() + "）");
        }
        String normalizedSummary = (summary == null || summary.isBlank()) ? null : summary.strip();
        return new VoucherLine(null, lineNo, accountCode.strip(), normalizedDebit, normalizedCredit,
                normalizedSummary);
    }

    /** 持久层重建工厂（不重跑业务校验，库中数据以入库时校验为准） */
    public static VoucherLine restore(long id, int lineNo, String accountCode, BigDecimal debit,
                                      BigDecimal credit, String summary) {
        return new VoucherLine(id, lineNo, accountCode, debit, credit, summary);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("凭证行 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    private static BigDecimal normalizeAmount(BigDecimal amount, String fieldName) {
        BigDecimal value = (amount == null) ? BigDecimal.ZERO : amount;
        if (value.signum() < 0) {
            throw new IllegalArgumentException(fieldName + "不能为负: " + value.toPlainString());
        }
        if (value.stripTrailingZeros().scale() > CostingStrategy.AMOUNT_SCALE) {
            throw new IllegalArgumentException(fieldName + "最多 " + CostingStrategy.AMOUNT_SCALE
                    + " 位小数: " + value.toPlainString());
        }
        return value.setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }

    public Long getId() {
        return id;
    }

    public int getLineNo() {
        return lineNo;
    }

    public String getAccountCode() {
        return accountCode;
    }

    public BigDecimal getDebit() {
        return debit;
    }

    public BigDecimal getCredit() {
        return credit;
    }

    public String getSummary() {
        return summary;
    }
}
