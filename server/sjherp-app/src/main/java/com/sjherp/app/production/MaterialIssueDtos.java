package com.sjherp.app.production;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.sjherp.domain.production.MaterialIssue;
import com.sjherp.domain.production.MaterialIssueLine;
import com.sjherp.domain.production.MaterialIssueLineInput;

/**
 * 领料单 REST DTO 集（M5-T04）。请求/响应均以内部类形式聚合，照 WorkOrderDtos 范式。
 * 金额/数量一律 {@code toPlainString()} 序列化。
 */
public final class MaterialIssueDtos {

    private MaterialIssueDtos() {}

    // ================================================================ 请求 DTO

    /** POST /api/production/material-issues 建单请求体。 */
    public record CreateRequest(
            @NotBlank String workOrderDocNo,
            @NotNull Long warehouseId,
            String remark,
            @NotEmpty @Valid List<LineInput> lines
    ) {
        /** 单行请求。 */
        public record LineInput(
                @NotNull Long productId,
                @NotNull @DecimalMin("0") BigDecimal requiredQty,
                @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
                @NotNull Long unitId
        ) {}
    }

    // ================================================================ 响应 DTO

    /** 单行响应（含 issuedCost，未过账时为 null）。 */
    public record LineResponse(
            long lineNo,
            long productId,
            String requiredQty,
            String quantity,
            long unitId,
            String issuedCost
    ) {
        public static LineResponse from(MaterialIssueLine line) {
            return new LineResponse(
                    line.getLineNo(),
                    line.getProductId(),
                    line.getRequiredQty().toPlainString(),
                    line.getQuantity().toPlainString(),
                    line.getUnitId(),
                    line.getIssuedCost() == null ? null : line.getIssuedCost().toPlainString()
            );
        }
    }

    /** 领料单响应体（含行列表）。 */
    public record IssueResponse(
            String docNo,
            String workOrderDocNo,
            long warehouseId,
            String status,
            String remark,
            String totalIssuedCost,
            List<LineResponse> lines
    ) {
        public static IssueResponse from(MaterialIssue mi) {
            return new IssueResponse(
                    mi.getDocNo(),
                    mi.getWorkOrderDocNo(),
                    mi.getWarehouseId(),
                    mi.getStatus().name(),
                    mi.getRemark(),
                    mi.totalIssuedCost().toPlainString(),
                    mi.getLines().stream().map(LineResponse::from).toList()
            );
        }
    }

    /** 将领域对象转为 MaterialIssueLineInput（请求→领域转换工具）。 */
    public static MaterialIssueLineInput toInput(CreateRequest.LineInput r) {
        return new MaterialIssueLineInput(r.productId(), r.requiredQty(), r.quantity(), r.unitId());
    }
}
