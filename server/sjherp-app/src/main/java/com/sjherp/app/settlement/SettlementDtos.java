package com.sjherp.app.settlement;

import java.math.BigDecimal;
import java.util.List;

import com.sjherp.domain.settlement.SettlementRecord;

/**
 * 核销记录查询 API 的响应 DTO（M4-T03）。
 *
 * <p>精度约定（CLAUDE.md 原则 5）：金额一律以<b>字符串</b>承载（{@code BigDecimal#toPlainString}）。
 * paymentDocNo 可空（T03 恒 null，T04 收付款单回填后非空）。
 */
public final class SettlementDtos {

    private SettlementDtos() {
    }

    /** 核销记录列表响应（某应收/应付的全部核销轨迹，按发生先后）。 */
    public record SettlementListResponse(List<SettlementItem> items) {

        static SettlementListResponse from(List<SettlementRecord> records) {
            return new SettlementListResponse(records.stream().map(SettlementItem::from).toList());
        }
    }

    /** 单条核销记录。 */
    public record SettlementItem(long id, String type, long targetId, String targetSourceDocNo,
                                 String amount, String settlementDate, String paymentDocNo,
                                 String createdBy, String createdAt) {

        static SettlementItem from(SettlementRecord r) {
            return new SettlementItem(
                    r.getId() == null ? 0L : r.getId(),
                    r.getType().name(),
                    r.getTargetId(),
                    r.getTargetSourceDocNo(),
                    plain(r.getAmount()),
                    r.getSettlementDate() == null ? null : r.getSettlementDate().toString(),
                    r.getPaymentDocNo(),
                    r.getCreatedBy(),
                    r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
