package com.sjherp.app.gap;

import java.util.List;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.gap.GapRecord;

import jakarta.validation.constraints.NotBlank;

/**
 * 流程缺口 API 的请求/响应 DTO。
 */
public final class GapDtos {

    private GapDtos() {
    }

    /** 状态流转请求（开发侧用，target 为 GapStatus 枚举名） */
    public record StatusTransitionRequest(
            @NotBlank(message = "目标状态不能为空") String status) {
    }

    public record GapResponse(long id, String gapNo, String sessionId, String title,
                              String scenario, String expectedBehavior, String missingCapability,
                              String businessModule, String severity, String status, String reporter,
                              String createdBy, String createdAt, String updatedBy, String updatedAt) {

        static GapResponse from(GapRecord record) {
            return new GapResponse(
                    record.getId(),
                    record.getGapNo(),
                    record.getSessionId(),
                    record.getTitle(),
                    record.getScenario(),
                    record.getExpectedBehavior(),
                    record.getMissingCapability(),
                    record.getBusinessModule().name(),
                    record.getSeverity().name(),
                    record.getStatus().name(),
                    record.getReporter(),
                    record.getCreatedBy(),
                    record.getCreatedAt().toString(),
                    record.getUpdatedBy(),
                    record.getUpdatedAt().toString());
        }
    }

    /** 分页响应（与领域层 PageResult 同构，约定同 catalog） */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {

        static PageResponse<GapResponse> fromGaps(PageResult<GapRecord> result) {
            return new PageResponse<>(
                    result.items().stream().map(GapResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }
}
