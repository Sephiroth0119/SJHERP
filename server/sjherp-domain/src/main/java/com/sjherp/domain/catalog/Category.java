package com.sjherp.domain.catalog;

import java.time.Instant;
import java.util.Objects;

/**
 * 商品类目档案（树形）。
 *
 * <p>小企业从简：最多 {@value #MAX_LEVEL} 层（层级 level 从 1 开始计）。
 * 层级在创建时由父类目层级 + 1 确定并固化；为避免"挪动子树导致超层"
 * 的复杂校验，类目**不支持变更父类目**（建错了删掉重建，删除有引用保护）。
 */
public final class Category {

    /** 树形层级上限（小企业从简，最多 3 层） */
    public static final int MAX_LEVEL = 3;

    private static final int NAME_MAX_LENGTH = 100;

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    private String name;

    /** 父类目 id；null 表示根类目 */
    private final Long parentId;

    /** 树形层级（根 = 1），创建时固化 */
    private final int level;

    private final String createdBy;
    private final Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;

    /**
     * 新建类目。层级合法性（父存在、不超 {@value #MAX_LEVEL} 层）由
     * {@link CategoryService} 结合仓储校验后传入 level。
     */
    Category(String name, Long parentId, int level, String operator) {
        this.name = validateName(name);
        if (level < 1 || level > MAX_LEVEL) {
            throw new IllegalArgumentException(
                    "类目层级必须在 1-" + MAX_LEVEL + " 之间: " + level);
        }
        if ((parentId == null) != (level == 1)) {
            throw new IllegalArgumentException("根类目层级必须为 1，子类目层级必须 > 1");
        }
        this.parentId = parentId;
        this.level = level;
        this.createdBy = Objects.requireNonNull(operator, "operator 不能为空");
        this.createdAt = Instant.now();
        this.updatedBy = operator;
        this.updatedAt = this.createdAt;
    }

    private Category(Long id, String name, Long parentId, int level,
                     String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.parentId = parentId;
        this.level = level;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    /** 持久层重建工厂（不重跑业务校验，库中数据以入库时校验为准） */
    public static Category restore(long id, String name, Long parentId, int level,
                                   String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
        return new Category(id, name, parentId, level, createdBy, createdAt, updatedBy, updatedAt);
    }

    /** 重命名（父类目与层级不可变更，见类注释） */
    public void rename(String name, String operator) {
        this.name = validateName(name);
        this.updatedBy = Objects.requireNonNull(operator, "operator 不能为空");
        this.updatedAt = Instant.now();
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("类目 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("类目名称不能为空");
        }
        String trimmed = name.strip();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("类目名称不能超过 " + NAME_MAX_LENGTH + " 个字符");
        }
        return trimmed;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Long getParentId() {
        return parentId;
    }

    public int getLevel() {
        return level;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
