package com.sjherp.app.production;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.DemandPlan;
import com.sjherp.domain.production.DemandPlanCommand;
import com.sjherp.domain.production.DemandPlanLine;
import com.sjherp.domain.production.DemandPlanLineCommand;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 需求计划 API 的请求/响应 DTO（M5-T02）。
 *
 * <p>口径同 BomDtos：Bean Validation 做字段级格式校验，业务规则校验仍在领域层；
 * BigDecimal 数量以 toPlainString() 呈现；状态用枚举英文名。
 */
public final class DemandPlanDtos {

    private DemandPlanDtos() {
    }

    // ================================================================ 行请求

    public record DemandPlanLineRequest(
            @NotNull(message = "商品 id 不能为空") Long productId,
            @NotNull(message = "需求数量不能为空")
            @DecimalMin(value = "0.000001", message = "需求数量必须大于 0") BigDecimal quantity,
            @NotNull(message = "单位 id 不能为空") Long unitId,
            LocalDate dueDate) {

        /** 转换为领域命令 */
        public DemandPlanLineCommand toCommand() {
            return new DemandPlanLineCommand(productId, quantity, unitId, dueDate);
        }
    }

    // ================================================================ 头请求

    public record DemandPlanRequest(
            @NotNull(message = "计划日期不能为空") LocalDate planDate,
            String remark,
            @NotNull(message = "行列表不能为空")
            @NotEmpty(message = "需求计划至少需要一行")
            @Valid List<DemandPlanLineRequest> lines) {

        /** 将请求 DTO 转换为领域命令 */
        public DemandPlanCommand toCommand() {
            return new DemandPlanCommand(
                    planDate,
                    remark,
                    lines.stream().map(DemandPlanLineRequest::toCommand).toList());
        }
    }

    // ================================================================ 行响应

    public record DemandPlanLineResponse(
            long productId,
            String quantity,
            long unitId,
            String dueDate) {

        public static DemandPlanLineResponse from(DemandPlanLine line) {
            return new DemandPlanLineResponse(
                    line.productId(),
                    line.quantity().toPlainString(),
                    line.unitId(),
                    line.dueDate() != null ? line.dueDate().toString() : null);
        }
    }

    // ================================================================ 头响应

    public record DemandPlanResponse(
            long id,
            String docNo,
            String planDate,
            String status,
            String remark,
            List<DemandPlanLineResponse> lines,
            String createdBy,
            String createdAt,
            String updatedBy,
            String updatedAt) {

        public static DemandPlanResponse from(DemandPlan plan) {
            return new DemandPlanResponse(
                    plan.getId(),
                    plan.getDocNo(),
                    plan.getPlanDate().toString(),
                    plan.getStatus().name(),
                    plan.getRemark(),
                    plan.getLines().stream().map(DemandPlanLineResponse::from).toList(),
                    plan.getCreatedBy(),
                    plan.getCreatedAt().toString(),
                    plan.getUpdatedBy(),
                    plan.getUpdatedAt().toString());
        }
    }

    // ================================================================ 分页响应

    /** 分页响应（与领域层 PageResult 同构） */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {

        public static <T> PageResponse<T> from(PageResult<T> page) {
            return new PageResponse<>(page.items(), page.total(), page.page(), page.size());
        }

        /** 从需求计划分页结果构建响应 */
        public static PageResponse<DemandPlanResponse> fromPlans(PageResult<DemandPlan> result) {
            return new PageResponse<>(
                    result.items().stream().map(DemandPlanResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }
}
