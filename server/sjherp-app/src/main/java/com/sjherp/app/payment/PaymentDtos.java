package com.sjherp.app.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.payment.PaymentDisbursement;
import com.sjherp.domain.payment.PaymentDisbursementLine;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 付款单（PaymentDisbursement）API 的请求/响应 DTO（M4-T04b）。
 *
 * <p>精度约定（CLAUDE.md 原则 5）：金额在 JSON 中一律以<b>字符串</b>承载
 * （BigDecimal#toPlainString），绝不用 JSON 数字——与采购/销售/库存/收款 API 同口径。
 */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    /** 建单请求：供应商 + 付出资金账户 + 付款日期（可空默认今天）+ 说明（可空）+ 分摊行数组 */
    public record CreatePaymentDisbursementRequest(
            @NotNull(message = "供应商 id 不能为空") Long supplierId,
            @NotNull(message = "付出的资金账户 id 不能为空") Long paymentAccountId,
            LocalDate paymentDate,
            String remark,
            @NotEmpty(message = "付款单至少要有一行") @Valid List<PaymentDisbursementLineRequest> lines) {
    }

    /** 建单行输入：分摊到的应付账款主键（必填）+ 分摊金额（必填，> 0） */
    public record PaymentDisbursementLineRequest(
            @NotNull(message = "分摊行引用的应付账款 id 不能为空") Long payableId,
            @NotNull(message = "分摊金额不能为空") BigDecimal allocatedAmount) {
    }

    /** 付款单响应（单据头 + 分摊行） */
    public record PaymentDisbursementResponse(String docNo, long supplierId, long paymentAccountId,
                                              LocalDate paymentDate, String remark, String status,
                                              String totalAmount,
                                              List<PaymentDisbursementLineResponse> lines) {

        public static PaymentDisbursementResponse from(PaymentDisbursement disbursement) {
            List<PaymentDisbursementLineResponse> lines = disbursement.getLines().stream()
                    .map(PaymentDisbursementLineResponse::from).toList();
            return new PaymentDisbursementResponse(disbursement.getDocNo(), disbursement.getSupplierId(),
                    disbursement.getPaymentAccountId(), disbursement.getPaymentDate(),
                    disbursement.getRemark(), disbursement.getStatus().name(),
                    plain(disbursement.totalAmount()), lines);
        }
    }

    /** 付款单分摊行响应：应付 id + 分摊金额（字符串） */
    public record PaymentDisbursementLineResponse(int lineNo, long payableId, String allocatedAmount) {

        static PaymentDisbursementLineResponse from(PaymentDisbursementLine line) {
            return new PaymentDisbursementLineResponse(line.getLineNo(), line.getPayableId(),
                    plain(line.getAllocatedAmount()));
        }
    }

    /** 分页响应（与采购/销售/库存/收款 API 同构） */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {

        static PageResponse<PaymentDisbursementResponse> fromDisbursements(
                PageResult<PaymentDisbursement> result) {
            return new PageResponse<>(
                    result.items().stream().map(PaymentDisbursementResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
