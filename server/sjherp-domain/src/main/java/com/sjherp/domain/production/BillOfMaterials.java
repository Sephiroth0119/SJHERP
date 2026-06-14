package com.sjherp.domain.production;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.sjherp.domain.common.audit.AuditTarget;
import com.sjherp.domain.common.ArchiveStatus;

/**
 * BOM（物料清单）聚合根（M5-T01）。
 *
 * <p>自然键：(product_id, version)——同产品可多版本，但至多一条 ENABLED（数据库生成列 + 唯一索引兜底）。
 * 创建默认 ENABLED，同时停用同产品其他启用版本（由 {@link BillOfMaterialsService} 同事务完成）。
 *
 * <p>不可妥协原则 1：所有写操作经本聚合根或服务层，不可绕过直接写库。
 * 不可妥协原则 2：状态机——主数据用 {@link ArchiveStatus}（非单据状态机）；
 * 重复启停抛 {@link IllegalArgumentException}，由上层 409/400 处理。
 */
public class BillOfMaterials implements AuditTarget {

    private Long id;
    private long productId;
    private int version;
    private ArchiveStatus status;
    private String remark;
    private List<BomLine> lines;

    private String createdBy;
    private Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;

    // ================================================================ 构造（公开，含校验）

    /**
     * 新建 BOM（对外唯一业务构造器）。
     *
     * @param productId 父件商品 id
     * @param version   版本号（同 productId 内唯一，正整数）
     * @param remark    备注（可空）
     * @param lines     BOM 行（不能为空，子件不重复，不能自引用父件）
     * @param operator  操作人
     */
    public BillOfMaterials(long productId, int version, String remark,
                           List<BomLine> lines, String operator) {
        Objects.requireNonNull(lines, "BOM 行列表不能为空");
        Objects.requireNonNull(operator, "操作人不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("BOM 至少需要一行子件");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("BOM 版本号必须为正整数: " + version);
        }
        validateLines(productId, lines);

        this.productId = productId;
        this.version = version;
        this.status = ArchiveStatus.ENABLED; // 创建默认启用（小企业一步到位）
        this.remark = remark;
        this.lines = List.copyOf(lines);
        Instant now = Instant.now();
        this.createdBy = operator;
        this.createdAt = now;
        this.updatedBy = operator;
        this.updatedAt = now;
    }

    // ================================================================ 恢复（私有，不校验）

    private BillOfMaterials() {
    }

    /**
     * 从持久化状态恢复聚合根（不重跑业务校验，由仓储调用）。
     */
    public static BillOfMaterials restore(Long id, long productId, int version, ArchiveStatus status,
                                          String remark, List<BomLine> lines,
                                          String createdBy, Instant createdAt,
                                          String updatedBy, Instant updatedAt) {
        BillOfMaterials bom = new BillOfMaterials();
        bom.id = id;
        bom.productId = productId;
        bom.version = version;
        bom.status = status;
        bom.remark = remark;
        bom.lines = List.copyOf(lines);
        bom.createdBy = createdBy;
        bom.createdAt = createdAt;
        bom.updatedBy = updatedBy;
        bom.updatedAt = updatedAt;
        return bom;
    }

    // ================================================================ 业务行为

    /**
     * 更新 BOM 内容（重置行列表，校验行完整性和自引用）。
     * version 不可改（版本标识一旦确定不可变，变内容则版本语义失真）。
     */
    public void update(String remark, List<BomLine> lines, String operator) {
        Objects.requireNonNull(lines, "BOM 行列表不能为空");
        Objects.requireNonNull(operator, "操作人不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("BOM 至少需要一行子件");
        }
        validateLines(this.productId, lines);
        this.remark = remark;
        this.lines = List.copyOf(lines);
        touch(operator);
    }

    /** 启用（重复启用拒绝，由 Service 层同事务先停同产品其他版本） */
    public void enable(String operator) {
        if (this.status == ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("BOM [" + productId + " v" + version + "] 已是启用状态，无需重复启用");
        }
        this.status = ArchiveStatus.ENABLED;
        touch(operator);
    }

    /** 停用（重复停用拒绝） */
    public void disable(String operator) {
        if (this.status == ArchiveStatus.DISABLED) {
            throw new IllegalArgumentException("BOM [" + productId + " v" + version + "] 已是停用状态，无需重复停用");
        }
        this.status = ArchiveStatus.DISABLED;
        touch(operator);
    }

    /** 落库后回填主键（只允许从 null 赋值一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("BOM id 已赋值，不可重复赋值: " + this.id);
        }
        this.id = id;
    }

    // ================================================================ 校验辅助

    /** 校验行列表：子件 id 不重复、不自引用父件 */
    private static void validateLines(long parentProductId, List<BomLine> lines) {
        Set<Long> childIds = new HashSet<>();
        for (BomLine line : lines) {
            if (line.childProductId() == parentProductId) {
                throw new IllegalArgumentException(
                        "BOM 行不能引用父件自身: productId=" + parentProductId);
            }
            if (!childIds.add(line.childProductId())) {
                throw new IllegalArgumentException(
                        "BOM 行子件重复: childProductId=" + line.childProductId());
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
        return "BOM-" + productId + "-v" + version;
    }

    @Override
    public String auditSummary() {
        return "BOM productId=" + productId + " version=" + version + " status=" + status
                + " lines=" + lines.size();
    }

    // ================================================================ Getters

    public Long getId() { return id; }
    public long getProductId() { return productId; }
    public int getVersion() { return version; }
    public ArchiveStatus getStatus() { return status; }
    public String getRemark() { return remark; }
    public List<BomLine> getLines() { return lines; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
