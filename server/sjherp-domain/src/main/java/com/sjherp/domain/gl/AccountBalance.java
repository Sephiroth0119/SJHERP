package com.sjherp.domain.gl;

import java.math.BigDecimal;
import java.util.Objects;

import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 科目余额/试算平衡派生值（M4-T01）：某账期某科目已过账凭证行的借贷汇总。
 *
 * <p>T01 试算/余额从<b>已过账（APPROVED）</b>凭证行 SUM 派生，不维护期末余额表
 * （维护型 account_period_balance 留 T05 关账冻结期末用，拆解 §8 决策 2）。净额按
 * 「借 − 贷」计（借方科目余额正、贷方科目余额负，调用方按 {@link Account#getBalanceDir}
 * 解读方向）。
 *
 * @param accountCode 科目编码
 * @param totalDebit  本期借方发生额合计（2 位小数）
 * @param totalCredit 本期贷方发生额合计（2 位小数）
 */
public record AccountBalance(String accountCode, BigDecimal totalDebit, BigDecimal totalCredit) {

    public AccountBalance {
        Objects.requireNonNull(accountCode, "科目编码不能为空");
        totalDebit = normalize(totalDebit);
        totalCredit = normalize(totalCredit);
    }

    /** 净额 = Σ借 − Σ贷（2 位小数；借方科目正、贷方科目负，调用方按余额方向解读） */
    public BigDecimal netBalance() {
        return totalDebit.subtract(totalCredit)
                .setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }

    private static BigDecimal normalize(BigDecimal amount) {
        BigDecimal value = (amount == null) ? BigDecimal.ZERO : amount;
        return value.setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }
}
