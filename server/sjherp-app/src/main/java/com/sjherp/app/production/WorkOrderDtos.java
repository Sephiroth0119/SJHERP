package com.sjherp.app.production;

import java.time.LocalDate;
import java.util.List;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.WorkOrder;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * 工单 HTTP 层 DTO（M5-T03）。
 *
 * <p>所有金额/数量使用 {@code String}（toPlainString），避免 JSON 精度丢失。
 */
public final class WorkOrderDtos {

    private WorkOrderDtos() {}

    // ---------------------------------------------------------------- 请求

    /** 手工建单请求 */
    public record CreateWorkOrderRequest(
            @NotNull(message = "productId 必填") Long productId,
            @NotNull(message = "plannedQty 必填")
            @DecimalMin(value = "0.000001", message = "计划数量必须大于 0") java.math.BigDecimal plannedQty,
            @NotNull(message = "unitId 必填") Long unitId,
            Integer bomVersion,
            Integer routingVersion,
            Long warehouseId,
            LocalDate plannedStartDate,
            LocalDate plannedEndDate,
            String remark) {
    }

    /** 从 MRP 建议建单请求 */
    public record CreateFromSuggestionRequest(
            @NotNull(message = "mrpRunDocNo 必填") String mrpRunDocNo,
            @NotNull(message = "productId 必填") Long productId) {
    }

    // ---------------------------------------------------------------- 响应

    /** 工单完整响应（建单/查详情） */
    public record WorkOrderResponse(
            Long id,
            String docNo,
            long productId,
            String plannedQty,
            long unitId,
            String completedQty,
            Integer bomVersion,
            Integer routingVersion,
            Long warehouseId,
            String mrpRunDocNo,
            String sourceType,
            LocalDate plannedStartDate,
            LocalDate plannedEndDate,
            String remark,
            String status,
            String createdBy) {

        public static WorkOrderResponse from(WorkOrder wo) {
            return new WorkOrderResponse(
                    wo.getId(),
                    wo.getDocNo(),
                    wo.getProductId(),
                    wo.getPlannedQty().toPlainString(),
                    wo.getUnitId(),
                    wo.getCompletedQty().toPlainString(),
                    wo.getBomVersion(),
                    wo.getRoutingVersion(),
                    wo.getWarehouseId(),
                    wo.getMrpRunDocNo(),
                    wo.getSourceType().name(),
                    wo.getPlannedStartDate(),
                    wo.getPlannedEndDate(),
                    wo.getRemark(),
                    wo.getStatus().name(),
                    wo.getCreatedBy());
        }
    }

    /** 工单列表摘要响应（分页列表） */
    public record WorkOrderSummaryResponse(
            Long id,
            String docNo,
            long productId,
            String plannedQty,
            long unitId,
            String sourceType,
            String status,
            String createdBy) {

        public static WorkOrderSummaryResponse from(WorkOrder wo) {
            return new WorkOrderSummaryResponse(
                    wo.getId(),
                    wo.getDocNo(),
                    wo.getProductId(),
                    wo.getPlannedQty().toPlainString(),
                    wo.getUnitId(),
                    wo.getSourceType().name(),
                    wo.getStatus().name(),
                    wo.getCreatedBy());
        }
    }

    /** 通用分页响应（自包含，避免跨 DTO 文件依赖） */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {

        public static <T> PageResponse<T> from(PageResult<T> result) {
            return new PageResponse<>(result.items(), result.total(), result.page(), result.size());
        }
    }
}
