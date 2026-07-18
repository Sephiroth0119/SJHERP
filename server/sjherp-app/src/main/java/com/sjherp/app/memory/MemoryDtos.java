package com.sjherp.app.memory;

import java.time.Instant;
import java.util.List;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemoryEntryCommand;
import com.sjherp.domain.memory.MemorySourceType;
import com.sjherp.domain.memory.MemoryType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 大记忆管理 API 的请求与响应契约。 */
public final class MemoryDtos {

    private MemoryDtos() {
    }

    /** 人工新建或版本替换请求；业务规则仍由 MemoryService 和聚合根校验。 */
    public record CreateMemoryRequest(
            @NotNull(message = "记忆类型不能为空") MemoryType type,
            @NotBlank(message = "标题不能为空")
            @Size(max = 200, message = "标题不能超过 200 个字符") String title,
            @NotBlank(message = "记忆原文不能为空")
            @Size(max = 1_000_000, message = "记忆原文不能超过 1000000 个字符") String content,
            @NotNull(message = "来源类型不能为空") MemorySourceType sourceType,
            @NotBlank(message = "来源编号不能为空")
            @Size(max = 128, message = "来源编号不能超过 128 个字符") String sourceRef,
            Instant validFrom,
            Instant validTo) {

        MemoryEntryCommand toCommand() {
            return new MemoryEntryCommand(type, title, content, sourceType, sourceRef,
                    validFrom, validTo);
        }
    }

    /**
     * MySQL 真源管理视图。包含可审计的原文和派生索引状态，但绝不返回向量。
     */
    public record MemoryResponse(
            long id,
            String memoryNo,
            String memoryKey,
            int version,
            Long previousId,
            String type,
            String title,
            String content,
            String contentHash,
            String sourceType,
            String sourceRef,
            String status,
            Instant validFrom,
            Instant validTo,
            String indexStatus,
            String indexedCollection,
            String embeddingModel,
            Integer embeddingDimension,
            int retryCount,
            Instant nextRetryAt,
            String lastIndexError,
            String createdBy,
            Instant createdAt,
            String updatedBy,
            Instant updatedAt) {

        static MemoryResponse from(MemoryEntry entry) {
            if (entry.getId() == null) {
                throw new IllegalStateException("大记忆响应缺少持久化主键: " + entry.getMemoryNo());
            }
            return new MemoryResponse(entry.getId(), entry.getMemoryNo(), entry.getMemoryKey(),
                    entry.getVersion(), entry.getPreviousId(), entry.getMemoryType().name(),
                    entry.getTitle(), entry.getContent(), entry.getContentHash(),
                    entry.getSourceType().name(), entry.getSourceRef(), entry.getStatus().name(),
                    entry.getValidFrom(), entry.getValidTo(), entry.getIndexStatus().name(),
                    entry.getIndexedCollection(), entry.getEmbeddingModel(),
                    entry.getEmbeddingDimension(), entry.getRetryCount(), entry.getNextRetryAt(),
                    entry.getLastIndexError(), entry.getCreatedBy(), entry.getCreatedAt(),
                    entry.getUpdatedBy(), entry.getUpdatedAt());
        }
    }

    /** 分页管理结果。 */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {

        public PageResponse {
            items = List.copyOf(items);
        }

        static PageResponse<MemoryResponse> from(PageResult<MemoryEntry> result) {
            return new PageResponse<>(result.items().stream().map(MemoryResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }

    /** 全量重建执行摘要。 */
    public record RebuildResponse(int succeeded, int failed, long lastProcessedId) {

        static RebuildResponse from(MemoryIndexingService.RebuildResult result) {
            return new RebuildResponse(result.succeeded(), result.failed(), result.lastProcessedId());
        }
    }
}
