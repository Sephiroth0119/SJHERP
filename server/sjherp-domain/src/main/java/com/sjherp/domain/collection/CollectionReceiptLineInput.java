package com.sjherp.domain.collection;

import java.math.BigDecimal;

/**
 * 建单时单行的输入（M4-T04b）：分摊到的应收账款主键 + 分摊金额。
 *
 * @param receivableId    分摊到的应收账款主键（accounts_receivable.id）
 * @param allocatedAmount 分摊金额（> 0，2 位）
 */
public record CollectionReceiptLineInput(long receivableId, BigDecimal allocatedAmount) {
}
