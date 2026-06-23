package com.sjherp.app.production;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.sjherp.domain.production.ProductionReport;
import com.sjherp.domain.production.ProductionReportLine;
import com.sjherp.domain.production.ProductionReportLineInput;

/**
 * 报工单 REST DTO 集（M5-T05）。请求/响应均以内部类形式聚合，照 MaterialIssueDtos 范式。
 * 金额/数量一律 {@code toPlainString()} 序列化。
 */
public final class ProductionReportDtos {

    private ProductionReportDtos() {}

    // ================================================================ 请求 DTO

    /** POST /api/production/reports 建单请求体。 */
    public record CreateRequest(
            @NotBlank String workOrderDocNo,
            @NotNull Long warehouseId,
            @NotNull Long productId,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal completedQty,
            BigDecimal scrapQty,
            @NotNull Long unitId,
            String remark,
            @NotEmpty @Valid List<LineInput> lines
    ) {
        /** 工时行请求。 */
        public record LineInput(
                Integer operationSeqNo,
                String operationName,
                String workCenter,
                @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal reportedHours,
                BigDecimal reportedQty,
                @NotNull Long unitId
        ) {}
    }

    // ================================================================ 响应 DTO

    /** 工时行响应。 */
    public record LineResponse(
            long lineNo,
            Integer operationSeqNo,
            String operationName,
            String workCenter,
            String reportedHours,
            String reportedQty,
            long unitId
    ) {
        public static LineResponse from(ProductionReportLine line) {
            return new LineResponse(
                    line.getLineNo(),
                    line.getOperationSeqNo(),
                    line.getOperationName(),
                    line.getWorkCenter(),
                    line.getReportedHours().toPlainString(),
                    line.getReportedQty() == null ? null : line.getReportedQty().toPlainString(),
                    line.getUnitId()
            );
        }
    }

    /** 报工单响应体（含工时行列表）。 */
    public record ReportResponse(
            String docNo,
            String workOrderDocNo,
            long warehouseId,
            long productId,
            String completedQty,
            String scrapQty,
            long unitId,
            String inboundCost,
            String status,
            String remark,
            List<LineResponse> lines
    ) {
        public static ReportResponse from(ProductionReport pr) {
            return new ReportResponse(
                    pr.getDocNo(),
                    pr.getWorkOrderDocNo(),
                    pr.getWarehouseId(),
                    pr.getProductId(),
                    pr.getCompletedQty().toPlainString(),
                    pr.getScrapQty().toPlainString(),
                    pr.getUnitId(),
                    pr.getInboundCost() == null ? null : pr.getInboundCost().toPlainString(),
                    pr.getStatus().name(),
                    pr.getRemark(),
                    pr.getLines().stream().map(LineResponse::from).toList()
            );
        }
    }

    /** 将请求 DTO 行转为领域输入对象。 */
    public static ProductionReportLineInput toInput(CreateRequest.LineInput r) {
        return new ProductionReportLineInput(
                r.operationSeqNo(), r.operationName(), r.workCenter(),
                r.reportedHours(), r.reportedQty(), r.unitId());
    }
}
