package com.sjherp.domain.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

import com.sjherp.domain.common.audit.AuditTarget;

/**
 * 系统大记忆的 MySQL 真源聚合根。
 *
 * <p>每次内容替换生成新版本，旧版本只能进入 {@link MemoryStatus#SUPERSEDED}；
 * 逻辑失效进入 {@link MemoryStatus#EXPIRED}。聚合不提供物理删除，也不会把原文
 * 放入审计摘要或向量库载荷。
 */
public final class MemoryEntry implements AuditTarget {

    private static final int MEMORY_NO_MAX_LENGTH = 32;
    private static final int MEMORY_KEY_MAX_LENGTH = 128;
    private static final int TITLE_MAX_LENGTH = 200;
    private static final int SOURCE_REF_MAX_LENGTH = 128;
    private static final int OPERATOR_MAX_LENGTH = 64;
    private static final int INDEX_NAME_MAX_LENGTH = 128;
    private static final int MODEL_MAX_LENGTH = 128;
    private static final int INDEX_ERROR_MAX_LENGTH = 1000;

    private Long id;
    private final long tenantId;
    private final String memoryNo;
    private final String memoryKey;
    private final int version;
    private final Long previousId;
    private final MemoryType memoryType;
    private final String title;
    private final String content;
    private final String contentHash;
    private final MemorySourceType sourceType;
    private final String sourceRef;
    private MemoryStatus status;
    private final Instant validFrom;
    private Instant validTo;
    private MemoryIndexStatus indexStatus;
    private String indexedCollection;
    private String embeddingModel;
    private Integer embeddingDimension;
    private int retryCount;
    private Instant nextRetryAt;
    private String lastIndexError;
    private final String createdBy;
    private final Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;

    private MemoryEntry(Long id, long tenantId, String memoryNo, String memoryKey,
                        int version, Long previousId, MemoryType memoryType,
                        String title, String content, String contentHash,
                        MemorySourceType sourceType, String sourceRef, MemoryStatus status,
                        Instant validFrom, Instant validTo, MemoryIndexStatus indexStatus,
                        String indexedCollection, String embeddingModel,
                        Integer embeddingDimension, int retryCount, Instant nextRetryAt,
                        String lastIndexError, String createdBy, Instant createdAt,
                        String updatedBy, Instant updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.memoryNo = memoryNo;
        this.memoryKey = memoryKey;
        this.version = version;
        this.previousId = previousId;
        this.memoryType = memoryType;
        this.title = title;
        this.content = content;
        this.contentHash = contentHash;
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
        this.status = status;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.indexStatus = indexStatus;
        this.indexedCollection = indexedCollection;
        this.embeddingModel = embeddingModel;
        this.embeddingDimension = embeddingDimension;
        this.retryCount = retryCount;
        this.nextRetryAt = nextRetryAt;
        this.lastIndexError = lastIndexError;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    /** 新建首个活动版本；租户能力落地前使用项目统一的 tenantId=0。 */
    public static MemoryEntry create(String memoryNo, String memoryKey, int version,
                                     MemoryType memoryType, String title, String content,
                                     MemorySourceType sourceType, String sourceRef,
                                     Instant validFrom, Instant validTo,
                                     String operator, Instant now) {
        String normalizedContent = requireText(content, Integer.MAX_VALUE, "记忆原文");
        Instant checkedValidFrom = Objects.requireNonNull(validFrom, "生效时间不能为空");
        validateValidity(checkedValidFrom, validTo);
        String checkedOperator = requireText(operator, OPERATOR_MAX_LENGTH, "操作人");
        Instant checkedNow = Objects.requireNonNull(now, "当前时间不能为空");
        if (version < 1) {
            throw new IllegalArgumentException("版本必须 >= 1");
        }
        return new MemoryEntry(null, 0L,
                requireText(memoryNo, MEMORY_NO_MAX_LENGTH, "记忆编号"),
                requireText(memoryKey, MEMORY_KEY_MAX_LENGTH, "记忆键"),
                version, null, Objects.requireNonNull(memoryType, "记忆类型不能为空"),
                requireText(title, TITLE_MAX_LENGTH, "标题"), normalizedContent,
                sha256(normalizedContent),
                Objects.requireNonNull(sourceType, "来源类型不能为空"),
                requireText(sourceRef, SOURCE_REF_MAX_LENGTH, "来源编号"),
                MemoryStatus.ACTIVE, checkedValidFrom, validTo, MemoryIndexStatus.PENDING,
                null, null, null, 0, null, null,
                checkedOperator, checkedNow, checkedOperator, checkedNow);
    }

    /** 持久层按数据库快照重建聚合。 */
    public static MemoryEntry restore(long id, long tenantId, String memoryNo, String memoryKey,
                                      int version, Long previousId, MemoryType memoryType,
                                      String title, String content, String contentHash,
                                      MemorySourceType sourceType, String sourceRef,
                                      MemoryStatus status, Instant validFrom, Instant validTo,
                                      MemoryIndexStatus indexStatus, String indexedCollection,
                                      String embeddingModel, Integer embeddingDimension,
                                      int retryCount, Instant nextRetryAt, String lastIndexError,
                                      String createdBy, Instant createdAt,
                                      String updatedBy, Instant updatedAt) {
        return new MemoryEntry(id, tenantId, memoryNo, memoryKey, version, previousId,
                memoryType, title, content, contentHash, sourceType, sourceRef, status,
                validFrom, validTo, indexStatus, indexedCollection, embeddingModel,
                embeddingDimension, retryCount, nextRetryAt, lastIndexError,
                createdBy, createdAt, updatedBy, updatedAt);
    }

    /** 仓储插入后回填主键，只允许成功一次。 */
    public void assignId(long id) {
        if (id < 1) {
            throw new IllegalArgumentException("大记忆 id 必须为正数");
        }
        if (this.id != null) {
            throw new IllegalStateException("大记忆 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    /** 由新版本替代当前活动版本。 */
    public void markSuperseded(String operator, Instant now) {
        requireActive("替代");
        requireText(operator, OPERATOR_MAX_LENGTH, "操作人");
        Objects.requireNonNull(now, "当前时间不能为空");
        this.status = MemoryStatus.SUPERSEDED;
        this.validTo = now;
        touch(operator, now);
    }

    /** 逻辑失效当前活动版本。 */
    public void expire(String operator, Instant now) {
        requireActive("失效");
        requireText(operator, OPERATOR_MAX_LENGTH, "操作人");
        Objects.requireNonNull(now, "当前时间不能为空");
        this.status = MemoryStatus.EXPIRED;
        this.validTo = now;
        touch(operator, now);
    }

    /** 将活动版本重置为待索引，供人工重试或配置切换后重建。 */
    public void markPending(String operator, Instant now) {
        requireActive("重置索引");
        requireText(operator, OPERATOR_MAX_LENGTH, "操作人");
        Objects.requireNonNull(now, "当前时间不能为空");
        this.indexStatus = MemoryIndexStatus.PENDING;
        this.indexedCollection = null;
        this.embeddingModel = null;
        this.embeddingDimension = null;
        this.retryCount = 0;
        this.nextRetryAt = null;
        this.lastIndexError = null;
        touch(operator, now);
    }

    /** 记录派生索引成功及其完整规格。 */
    public void markIndexed(String collection, String model, int dimension,
                            String operator, Instant now) {
        requireActive("标记索引成功");
        if (dimension < 1) {
            throw new IllegalArgumentException("向量维度必须为正数");
        }
        String checkedCollection = requireText(collection, INDEX_NAME_MAX_LENGTH, "索引集合");
        String checkedModel = requireText(model, MODEL_MAX_LENGTH, "嵌入模型");
        requireText(operator, OPERATOR_MAX_LENGTH, "操作人");
        Objects.requireNonNull(now, "当前时间不能为空");
        this.indexStatus = MemoryIndexStatus.INDEXED;
        this.indexedCollection = checkedCollection;
        this.embeddingModel = checkedModel;
        this.embeddingDimension = dimension;
        this.retryCount = 0;
        this.nextRetryAt = null;
        this.lastIndexError = null;
        touch(operator, now);
    }

    /** 记录一次派生索引失败；原文绝不进入错误摘要。 */
    public void markIndexFailed(String error, Instant nextRetryAt,
                                String operator, Instant now) {
        requireActive("标记索引失败");
        String checkedError = requireText(error, INDEX_ERROR_MAX_LENGTH, "索引错误摘要");
        requireText(operator, OPERATOR_MAX_LENGTH, "操作人");
        Objects.requireNonNull(now, "当前时间不能为空");
        this.indexStatus = MemoryIndexStatus.FAILED;
        this.retryCount++;
        this.nextRetryAt = nextRetryAt;
        this.lastIndexError = checkedError;
        touch(operator, now);
    }

    private void requireActive(String action) {
        if (status != MemoryStatus.ACTIVE) {
            throw new IllegalStateException("仅活动记忆可" + action + "，当前状态: " + status);
        }
    }

    private void touch(String operator, Instant now) {
        this.updatedBy = requireText(operator, OPERATOR_MAX_LENGTH, "操作人");
        this.updatedAt = Objects.requireNonNull(now, "当前时间不能为空");
    }

    private static void validateValidity(Instant validFrom, Instant validTo) {
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("有效期结束时间不得早于开始时间");
        }
    }

    private static String requireText(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前运行时不支持 SHA-256", ex);
        }
    }

    public Long getId() { return id; }
    public long getTenantId() { return tenantId; }
    public String getMemoryNo() { return memoryNo; }
    public String getMemoryKey() { return memoryKey; }
    public int getVersion() { return version; }
    public Long getPreviousId() { return previousId; }
    public MemoryType getMemoryType() { return memoryType; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getContentHash() { return contentHash; }
    public MemorySourceType getSourceType() { return sourceType; }
    public String getSourceRef() { return sourceRef; }
    public MemoryStatus getStatus() { return status; }
    public Instant getValidFrom() { return validFrom; }
    public Instant getValidTo() { return validTo; }
    public MemoryIndexStatus getIndexStatus() { return indexStatus; }
    public String getIndexedCollection() { return indexedCollection; }
    public String getEmbeddingModel() { return embeddingModel; }
    public Integer getEmbeddingDimension() { return embeddingDimension; }
    public int getRetryCount() { return retryCount; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public String getLastIndexError() { return lastIndexError; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public Long auditTargetId() {
        return id;
    }

    @Override
    public String auditTargetCode() {
        return memoryNo;
    }

    @Override
    public String auditSummary() {
        return "编号=" + AuditTarget.text(memoryNo)
                + ", 记忆键=" + AuditTarget.text(memoryKey)
                + ", 版本=" + version
                + ", 类型=" + memoryType
                + ", 状态=" + status
                + ", 索引状态=" + indexStatus;
    }
}
