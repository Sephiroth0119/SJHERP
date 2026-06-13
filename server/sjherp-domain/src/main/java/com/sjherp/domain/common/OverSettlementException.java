package com.sjherp.domain.common;

import java.math.BigDecimal;

/**
 * 超额核销异常（M4-T03）：本次核销后已核销额将超过应收/应付原始金额时抛出。
 *
 * <p>置于 {@code com.sjherp.domain.common}（而非 receivable/payable）以避免
 * receivable/payable ↔ settlement 包之间的循环依赖——核销引擎与两个子账聚合都引用它。
 *
 * <p>继承 {@link IllegalArgumentException}：REST 层统一映射为 400（业务输入越界，非系统故障）。
 * CLAUDE.md 原则 2「财务记录只可冲销不可物理修改/删除」——超额核销即对子账的越界写入，硬拒绝。
 */
public class OverSettlementException extends IllegalArgumentException {

    public OverSettlementException(BigDecimal amount, BigDecimal alreadySettled, BigDecimal total) {
        super("核销金额超出未核销余额: 本次=" + plain(amount) + ", 已核销=" + plain(alreadySettled)
                + ", 原始金额=" + plain(total) + ", 未核销余额=" + plain(total.subtract(alreadySettled)));
    }

    private static String plain(BigDecimal value) {
        return value == null ? "null" : value.toPlainString();
    }
}
