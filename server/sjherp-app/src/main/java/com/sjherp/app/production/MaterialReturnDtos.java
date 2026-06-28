package com.sjherp.app.production;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.sjherp.domain.production.MaterialReturn;
import com.sjherp.domain.production.MaterialReturnLine;
import com.sjherp.domain.production.MaterialReturnLineInput;

/**
 * 退料单 REST DTO 集（M5-T04）。照 MaterialIssueDtos 范式。
 * 金额/数量一律 {@code toPlainString()} 序列化。
 */
public final class MaterialReturnDtos {

    private MaterialReturnDtos() {}

    // ================================================================ 请求 DTO

    /** POST /api/production/material-returns 建单请求体。 */
    public record CreateRequest(
            @NotBlank String materialIssueDocNo,
            @NotNull Long warehouseId,
            String remark,
            @NotEmpty @Valid List<LineInput> lines
    ) {
        /** 单行请求。 */
        public record LineInput(
                @NotNull Long productId,
                @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
                @NotNull Long unitId,
                Integer srcIssueLineNo
        ) {}
    }

    // ================================================================ 响应 DTO

    /** 单行响应（含 returnedCost，未过账时为 null）。 */
    public record LineResponse(
            long lineNo,
            long productId,
            String quantity,
            long unitId,
            Integer srcIssueLineNo,
            String returnedCost
    ) {
        public static LineResponse from(MaterialReturnLine line) {
            return new LineResponse(
                    line.getLineNo(),
                    line.getProductId(),
                    line.getQuantity().toPlainString(),
                    line.getUnitId(),
                    line.getSrcIssueLineNo(),
                    line.getReturnedCost() == null ? null : line.getReturnedCost().toPlainString()
            );
        }
    }

    /** 退料单响应体（含行列表）。 */
    public record ReturnResponse(
            String docNo,
            String materialIssueDocNo,
            long warehouseId,
            String status,
            String remark,
            String totalReturnedCost,
            List<LineResponse> lines
    ) {
        public static ReturnResponse from(MaterialReturn mr) {
            return new ReturnResponse(
                    mr.getDocNo(),
                    mr.getMaterialIssueDocNo(),
                    mr.getWarehouseId(),
                    mr.getStatus().name(),
                    mr.getRemark(),
                    mr.totalReturnedCost().toPlainString(),
                    mr.getLines().stream().map(LineResponse::from).toList()
            );
        }
    }

    /** 将请求行转为领域输入。 */
    public static MaterialReturnLineInput toInput(CreateRequest.LineInput r) {
        return new MaterialReturnLineInput(r.productId(), r.quantity(), r.unitId(), r.srcIssueLineNo());
    }
}
