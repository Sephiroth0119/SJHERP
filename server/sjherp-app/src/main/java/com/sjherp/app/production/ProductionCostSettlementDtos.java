package com.sjherp.app.production;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import com.sjherp.domain.production.ProductionCostSettlement;
import com.sjherp.domain.production.ProductionCostSettlementLine;
import com.sjherp.domain.production.ProductionCostSettlementLineInput;

/**
 * 月末成本结转单 REST DTO 集（M5-T06）。金额/数量一律 {@code toPlainString()} 序列化。
 */
public final class ProductionCostSettlementDtos {

    private ProductionCostSettlementDtos() {}

    // ================================================================ 请求 DTO

    /** POST /api/production/cost-settlements 建单请求体。 */
    public record CreateRequest(
            @NotBlank String period,
            String remark,
            @NotEmpty @Valid List<LineInput> lines
    ) {
        /** 结转行请求（每工单一行 + 期末在产数量/完工程度）。 */
        public record LineInput(
                @NotBlank String workOrderDocNo,
                BigDecimal wipQty,
                BigDecimal wipCompletionPct
        ) {}
    }

    // ================================================================ 响应 DTO

    /** 结转行响应。 */
    public record LineResponse(
            int lineNo,
            String workOrderDocNo,
            String materialCost,
            String laborCost,
            String overheadCost,
            String completedQty,
            String completedCost,
            String wipQty,
            String wipCompletionPct,
            String wipCost,
            String alreadyTransferred,
            String costAdjustIdemKey,
            String voucherDocNo
    ) {
        public static LineResponse from(ProductionCostSettlementLine line) {
            return new LineResponse(
                    line.getLineNo(),
                    line.getWorkOrderDocNo(),
                    line.getMaterialCost().toPlainString(),
                    line.getLaborCost().toPlainString(),
                    line.getOverheadCost().toPlainString(),
                    line.getCompletedQty().toPlainString(),
                    line.getCompletedCost().toPlainString(),
                    line.getWipQty().toPlainString(),
                    line.getWipCompletionPct().toPlainString(),
                    line.getWipCost().toPlainString(),
                    line.getAlreadyTransferred().toPlainString(),
                    line.getCostAdjustIdemKey(),
                    line.getVoucherDocNo()
            );
        }
    }

    /** 结转单响应体（含行列表）。 */
    public record SettlementResponse(
            String docNo,
            String period,
            String status,
            String remark,
            List<LineResponse> lines
    ) {
        public static SettlementResponse from(ProductionCostSettlement s) {
            return new SettlementResponse(
                    s.getDocNo(),
                    s.getPeriod(),
                    s.getStatus().name(),
                    s.getRemark(),
                    s.getLines().stream().map(LineResponse::from).toList()
            );
        }
    }

    /** 将请求行转为领域输入对象。 */
    public static ProductionCostSettlementLineInput toInput(CreateRequest.LineInput r) {
        return new ProductionCostSettlementLineInput(r.workOrderDocNo(), r.wipQty(), r.wipCompletionPct());
    }
}
