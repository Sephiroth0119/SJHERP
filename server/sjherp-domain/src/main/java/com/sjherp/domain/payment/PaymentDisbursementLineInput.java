package com.sjherp.domain.payment;

import java.math.BigDecimal;

/**
 * 建单时单行的输入（M4-T04b）：分摊到的应付账款主键 + 分摊金额。
 *
 * @param payableId       分摊到的应付账款主键（accounts_payable.id）
 * @param allocatedAmount 分摊金额（> 0，2 位）
 */
public record PaymentDisbursementLineInput(long payableId, BigDecimal allocatedAmount) {
}
