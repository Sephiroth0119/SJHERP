package com.sjherp.domain.gl;

import java.math.BigDecimal;

/**
 * 凭证借贷不平领域异常（M4-T01，验收①）：凭证级不变式校验失败时抛出 → REST 400。
 *
 * <p>继承 {@link IllegalArgumentException}：不平凭证连聚合都构造不出，到不了 repository.save
 * （CLAUDE.md 原则 2：借贷必平在领域层构造时强制）。携带 Σ借 / Σ贷便于排错与提示。
 */
public class VoucherNotBalancedException extends IllegalArgumentException {

    private final BigDecimal totalDebit;
    private final BigDecimal totalCredit;

    public VoucherNotBalancedException(BigDecimal totalDebit, BigDecimal totalCredit) {
        super("凭证借贷不平：Σ借=" + totalDebit.toPlainString() + ", Σ贷=" + totalCredit.toPlainString());
        this.totalDebit = totalDebit;
        this.totalCredit = totalCredit;
    }

    public VoucherNotBalancedException(String message) {
        super(message);
        this.totalDebit = null;
        this.totalCredit = null;
    }

    public BigDecimal getTotalDebit() {
        return totalDebit;
    }

    public BigDecimal getTotalCredit() {
        return totalCredit;
    }
}
