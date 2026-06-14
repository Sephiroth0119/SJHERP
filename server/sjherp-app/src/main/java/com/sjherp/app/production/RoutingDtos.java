package com.sjherp.app.production;

import java.util.List;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.Routing;
import com.sjherp.domain.production.RoutingCommand;
import com.sjherp.domain.production.RoutingOperation;
import com.sjherp.domain.production.RoutingOperationCommand;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 工艺路线 API 的请求/响应 DTO（Bean Validation 做字段级格式校验，
 * 业务规则校验仍在领域层，M5-T01）。
 *
 * <p>口径同 BomDtos：BigDecimal 数量/费率以 toPlainString() 呈现；
 * 分页响应复用 {@link BomDtos.PageResponse}。
 */
public final class RoutingDtos {

    private RoutingDtos() {
    }

    // ================================================================ 工序请求

    public record RoutingOperationRequest(
            @NotNull(message = "工序序号不能为空") @Min(value = 1, message = "工序序号必须 >= 1") Integer sequenceNo,
            @NotBlank(message = "工序名称不能为空") String operationName,
            @NotNull(message = "标准工时不能为空") java.math.BigDecimal standardHours,
            String workCenter,
            // 工作中心/费率为 M5-T06 成本归集预留字段，本批可空（与 workCenter 一致），不强制
            java.math.BigDecimal costRate) {

        /** 转换为领域命令 */
        public RoutingOperationCommand toCommand() {
            return new RoutingOperationCommand(
                    sequenceNo, operationName, standardHours, workCenter, costRate);
        }
    }

    // ================================================================ 工艺路线请求

    public record RoutingRequest(
            @NotNull(message = "产品 id 不能为空") Long productId,
            @NotNull(message = "版本号不能为空") @Min(value = 1, message = "版本号必须 >= 1") Integer version,
            String remark,
            @NotNull(message = "工序列表不能为空") @NotEmpty(message = "工艺路线至少需要一道工序") @Valid List<RoutingOperationRequest> operations) {

        /** 将请求 DTO 转换为领域命令 */
        public RoutingCommand toCommand() {
            return new RoutingCommand(
                    productId,
                    version,
                    remark,
                    operations.stream().map(RoutingOperationRequest::toCommand).toList());
        }
    }

    // ================================================================ 工序响应

    public record RoutingOperationResponse(
            int sequenceNo,
            String operationName,
            String standardHours,
            String workCenter,
            String costRate) {

        public static RoutingOperationResponse from(RoutingOperation op) {
            return new RoutingOperationResponse(
                    op.sequenceNo(),
                    op.operationName(),
                    op.standardHours().toPlainString(),
                    op.workCenter(),
                    op.costRate() == null ? null : op.costRate().toPlainString());
        }
    }

    // ================================================================ 工艺路线响应

    public record RoutingResponse(
            long id,
            long productId,
            int version,
            String status,
            String remark,
            List<RoutingOperationResponse> operations,
            String createdBy,
            String createdAt,
            String updatedBy,
            String updatedAt) {

        public static RoutingResponse from(Routing routing) {
            return new RoutingResponse(
                    routing.getId(),
                    routing.getProductId(),
                    routing.getVersion(),
                    routing.getStatus().name(),
                    routing.getRemark(),
                    routing.getOperations().stream().map(RoutingOperationResponse::from).toList(),
                    routing.getCreatedBy(),
                    routing.getCreatedAt().toString(),
                    routing.getUpdatedBy(),
                    routing.getUpdatedAt().toString());
        }
    }

    // ================================================================ 分页响应便捷方法

    /** 从工艺路线分页结果构建分页响应（复用 BomDtos.PageResponse） */
    public static BomDtos.PageResponse<RoutingResponse> fromRoutings(PageResult<Routing> result) {
        return new BomDtos.PageResponse<>(
                result.items().stream().map(RoutingResponse::from).toList(),
                result.total(), result.page(), result.size());
    }
}
