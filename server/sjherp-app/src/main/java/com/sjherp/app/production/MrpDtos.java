package com.sjherp.app.production;

import java.util.List;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.MrpRun;
import com.sjherp.domain.production.MrpRunRequest;
import com.sjherp.domain.production.MrpSuggestion;
import com.sjherp.domain.production.SuggestionType;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * MRP 运行 API 的请求/响应 DTO（M5-T02）。
 *
 * <p>口径同 BomDtos：BigDecimal 数量以 toPlainString() 呈现；
 * 建议类型 {@link SuggestionType} 以枚举英文名呈现。
 */
public final class MrpDtos {

    private MrpDtos() {
    }

    // ================================================================ 运行请求

    public record MrpRunHttpRequest(
            @NotNull(message = "仓库 id 不能为空") @Min(value = 1, message = "仓库 id 必须 > 0") Long warehouseId,
            boolean includeForecast,
            boolean includeSalesOrder,
            String remark) {

        /** 转换为领域请求 */
        public MrpRunRequest toRequest() {
            return new MrpRunRequest(warehouseId, includeForecast, includeSalesOrder, remark);
        }
    }

    // ================================================================ 建议行响应

    public record MrpSuggestionResponse(
            String type,
            long productId,
            int level,
            String grossRequirement,
            String onHand,
            String netRequirement,
            long baseUnitId) {

        public static MrpSuggestionResponse from(MrpSuggestion suggestion) {
            return new MrpSuggestionResponse(
                    suggestion.type().name(),
                    suggestion.productId(),
                    suggestion.level(),
                    suggestion.grossRequirement().toPlainString(),
                    suggestion.onHand().toPlainString(),
                    suggestion.netRequirement().toPlainString(),
                    suggestion.baseUnitId());
        }
    }

    // ================================================================ 运行结果响应

    public record MrpRunResponse(
            long id,
            String docNo,
            String runAt,
            long warehouseId,
            boolean includeForecast,
            boolean includeSalesOrder,
            String remark,
            String createdBy,
            List<MrpSuggestionResponse> suggestions) {

        public static MrpRunResponse from(MrpRun run) {
            return new MrpRunResponse(
                    run.getId(),
                    run.getDocNo(),
                    run.getRunAt().toString(),
                    run.getWarehouseId(),
                    run.isIncludeForecast(),
                    run.isIncludeSalesOrder(),
                    run.getRemark(),
                    run.getCreatedBy(),
                    run.getSuggestions().stream().map(MrpSuggestionResponse::from).toList());
        }
    }

    // ================================================================ 历史列表条目（头信息，不含建议行）

    public record MrpRunSummaryResponse(
            long id,
            String docNo,
            String runAt,
            long warehouseId,
            boolean includeForecast,
            boolean includeSalesOrder,
            String remark,
            String createdBy) {

        public static MrpRunSummaryResponse from(MrpRun run) {
            return new MrpRunSummaryResponse(
                    run.getId(),
                    run.getDocNo(),
                    run.getRunAt().toString(),
                    run.getWarehouseId(),
                    run.isIncludeForecast(),
                    run.isIncludeSalesOrder(),
                    run.getRemark(),
                    run.getCreatedBy());
        }
    }

    // ================================================================ 分页响应

    /** 分页响应（与领域层 PageResult 同构） */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {

        public static <T> PageResponse<T> from(PageResult<T> page) {
            return new PageResponse<>(page.items(), page.total(), page.page(), page.size());
        }

        /** 从 MRP 历史分页结果构建摘要响应列表 */
        public static PageResponse<MrpRunSummaryResponse> fromHistory(PageResult<MrpRun> result) {
            return new PageResponse<>(
                    result.items().stream().map(MrpRunSummaryResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }
}
