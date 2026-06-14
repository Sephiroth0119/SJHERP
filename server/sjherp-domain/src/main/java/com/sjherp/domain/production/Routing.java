package com.sjherp.domain.production;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.audit.AuditTarget;

/**
 * 工艺路线聚合根（M5-T01，极简版）。
 *
 * <p>自然键：(product_id, version)——同产品可多版本，至多一条 ENABLED（数据库生成列+唯一索引兜底）。
 * 工序列表有序（按 sequenceNo），同路线内 sequenceNo 唯一。
 *
 * <p>不可妥协原则 1：所有写操作经本聚合根或 {@link RoutingService}，不可绕过直接写库。
 */
public class Routing implements AuditTarget {

    private Long id;
    private long productId;
    private int version;
    private ArchiveStatus status;
    private String remark;
    private List<RoutingOperation> operations;

    private String createdBy;
    private Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;

    // ================================================================ 构造（公开，含校验）

    /**
     * 新建工艺路线。
     *
     * @param productId  产品 id
     * @param version    版本号（正整数）
     * @param remark     备注（可空）
     * @param operations 工序列表（不能为空，sequenceNo 唯一有序）
     * @param operator   操作人
     */
    public Routing(long productId, int version, String remark,
                   List<RoutingOperation> operations, String operator) {
        Objects.requireNonNull(operations, "工序列表不能为空");
        Objects.requireNonNull(operator, "操作人不能为空");
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("工艺路线至少需要一道工序");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("工艺路线版本号必须为正整数: " + version);
        }
        validateOperations(operations);

        this.productId = productId;
        this.version = version;
        this.status = ArchiveStatus.ENABLED; // 创建默认启用（小企业一步到位）
        this.remark = remark;
        this.operations = List.copyOf(operations);
        Instant now = Instant.now();
        this.createdBy = operator;
        this.createdAt = now;
        this.updatedBy = operator;
        this.updatedAt = now;
    }

    // ================================================================ 恢复（私有，不校验）

    private Routing() {
    }

    public static Routing restore(Long id, long productId, int version, ArchiveStatus status,
                                  String remark, List<RoutingOperation> operations,
                                  String createdBy, Instant createdAt,
                                  String updatedBy, Instant updatedAt) {
        Routing r = new Routing();
        r.id = id;
        r.productId = productId;
        r.version = version;
        r.status = status;
        r.remark = remark;
        r.operations = List.copyOf(operations);
        r.createdBy = createdBy;
        r.createdAt = createdAt;
        r.updatedBy = updatedBy;
        r.updatedAt = updatedAt;
        return r;
    }

    // ================================================================ 业务行为

    /** 更新工艺路线（工序整体替换） */
    public void update(String remark, List<RoutingOperation> operations, String operator) {
        Objects.requireNonNull(operations, "工序列表不能为空");
        Objects.requireNonNull(operator, "操作人不能为空");
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("工艺路线至少需要一道工序");
        }
        validateOperations(operations);
        this.remark = remark;
        this.operations = List.copyOf(operations);
        touch(operator);
    }

    /** 启用（重复启用拒绝） */
    public void enable(String operator) {
        if (this.status == ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException(
                    "工艺路线 [productId=" + productId + " v" + version + "] 已是启用状态，无需重复启用");
        }
        this.status = ArchiveStatus.ENABLED;
        touch(operator);
    }

    /** 停用（重复停用拒绝） */
    public void disable(String operator) {
        if (this.status == ArchiveStatus.DISABLED) {
            throw new IllegalArgumentException(
                    "工艺路线 [productId=" + productId + " v" + version + "] 已是停用状态，无需重复停用");
        }
        this.status = ArchiveStatus.DISABLED;
        touch(operator);
    }

    /** 落库后回填主键（只允许从 null 赋值一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("工艺路线 id 已赋值，不可重复赋值: " + this.id);
        }
        this.id = id;
    }

    // ================================================================ 校验辅助

    /** 校验工序列表：sequenceNo 在同路线内唯一 */
    private static void validateOperations(List<RoutingOperation> operations) {
        Set<Integer> seqNos = new HashSet<>();
        for (RoutingOperation op : operations) {
            if (!seqNos.add(op.sequenceNo())) {
                throw new IllegalArgumentException(
                        "工序序号重复: sequenceNo=" + op.sequenceNo());
            }
        }
    }

    private void touch(String operator) {
        this.updatedBy = operator;
        this.updatedAt = Instant.now();
    }

    // ================================================================ AuditTarget

    @Override
    public Long auditTargetId() {
        return id;
    }

    @Override
    public String auditTargetCode() {
        return "ROUTING-" + productId + "-v" + version;
    }

    @Override
    public String auditSummary() {
        return "Routing productId=" + productId + " version=" + version
                + " status=" + status + " operations=" + operations.size();
    }

    // ================================================================ Getters

    public Long getId() { return id; }
    public long getProductId() { return productId; }
    public int getVersion() { return version; }
    public ArchiveStatus getStatus() { return status; }
    public String getRemark() { return remark; }
    public List<RoutingOperation> getOperations() { return operations; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
