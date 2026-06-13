package com.sjherp.app.collection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.sjherp.domain.collection.CollectionReceipt;
import com.sjherp.domain.collection.CollectionReceiptLine;
import com.sjherp.domain.common.PageResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 收款单（CollectionReceipt）API 的请求/响应 DTO（M4-T04b）。
 *
 * <p>精度约定（CLAUDE.md 原则 5）：金额在 JSON 中一律以<b>字符串</b>承载
 * （BigDecimal#toPlainString），绝不用 JSON 数字——与采购/销售/库存 API 同口径。
 */
public final class CollectionDtos {

    private CollectionDtos() {
    }

    /** 建单请求：客户 + 收入资金账户 + 收款日期（可空默认今天）+ 说明（可空）+ 分摊行数组 */
    public record CreateCollectionReceiptRequest(
            @NotNull(message = "客户 id 不能为空") Long customerId,
            @NotNull(message = "收入的资金账户 id 不能为空") Long paymentAccountId,
            LocalDate receiptDate,
            String remark,
            @NotEmpty(message = "收款单至少要有一行") @Valid List<CollectionReceiptLineRequest> lines) {
    }

    /** 建单行输入：分摊到的应收账款主键（必填）+ 分摊金额（必填，> 0） */
    public record CollectionReceiptLineRequest(
            @NotNull(message = "分摊行引用的应收账款 id 不能为空") Long receivableId,
            @NotNull(message = "分摊金额不能为空") BigDecimal allocatedAmount) {
    }

    /** 收款单响应（单据头 + 分摊行） */
    public record CollectionReceiptResponse(String docNo, long customerId, long paymentAccountId,
                                            LocalDate receiptDate, String remark, String status,
                                            String totalAmount,
                                            List<CollectionReceiptLineResponse> lines) {

        public static CollectionReceiptResponse from(CollectionReceipt receipt) {
            List<CollectionReceiptLineResponse> lines = receipt.getLines().stream()
                    .map(CollectionReceiptLineResponse::from).toList();
            return new CollectionReceiptResponse(receipt.getDocNo(), receipt.getCustomerId(),
                    receipt.getPaymentAccountId(), receipt.getReceiptDate(), receipt.getRemark(),
                    receipt.getStatus().name(), plain(receipt.totalAmount()), lines);
        }
    }

    /** 收款单分摊行响应：应收 id + 分摊金额（字符串） */
    public record CollectionReceiptLineResponse(int lineNo, long receivableId, String allocatedAmount) {

        static CollectionReceiptLineResponse from(CollectionReceiptLine line) {
            return new CollectionReceiptLineResponse(line.getLineNo(), line.getReceivableId(),
                    plain(line.getAllocatedAmount()));
        }
    }

    /** 分页响应（与采购/销售/库存 API 同构） */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {

        static PageResponse<CollectionReceiptResponse> fromReceipts(
                PageResult<CollectionReceipt> result) {
            return new PageResponse<>(
                    result.items().stream().map(CollectionReceiptResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
