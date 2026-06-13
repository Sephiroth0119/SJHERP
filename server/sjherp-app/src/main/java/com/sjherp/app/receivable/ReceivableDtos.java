package com.sjherp.app.receivable;

import java.math.BigDecimal;
import java.util.List;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.receivable.AccountsReceivable;

/**
 * 应收账款 API 的响应 DTO（M3-T10）。
 *
 * <p>精度约定（CLAUDE.md 原则 5）：金额一律以<b>字符串</b>承载（BigDecimal#toPlainString）。
 */
public final class ReceivableDtos {

    private ReceivableDtos() {
    }

    /** 应收响应 */
    public record ReceivableResponse(long id, long customerId, String amount, String settledAmount,
                                     String openAmount, String sourceDocNo, String dueDate,
                                     String status) {

        public static ReceivableResponse from(AccountsReceivable receivable) {
            return new ReceivableResponse(
                    receivable.getId() == null ? 0L : receivable.getId(),
                    receivable.getCustomerId(),
                    plain(receivable.getAmount()),
                    plain(receivable.getSettledAmount()),
                    plain(receivable.openAmount()),
                    receivable.getSourceDocNo(),
                    receivable.getDueDate() == null ? null : receivable.getDueDate().toString(),
                    receivable.getStatus().name());
        }
    }

    /** 分页响应（与销售线 API 同构） */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {

        public static PageResponse<ReceivableResponse> from(PageResult<AccountsReceivable> result) {
            return new PageResponse<>(
                    result.items().stream().map(ReceivableResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
