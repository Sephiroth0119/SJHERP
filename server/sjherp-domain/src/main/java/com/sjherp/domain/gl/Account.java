package com.sjherp.domain.gl;

import java.time.Instant;
import java.util.Objects;

import com.sjherp.domain.common.audit.AuditTarget;

/**
 * 会计科目档案聚合根（M4-T01，参照 {@link com.sjherp.domain.catalog.Product} 两态档案）。
 *
 * <p>科目是凭证行挂账的对象：树形结构（按编码自关联 {@link #parentCode}），仅<b>末级</b>
 * 科目（{@link #isLeaf}）可挂凭证行。档案不是单据——没有"草稿→审核"生命周期，只有启用/停用两态
 * （{@link #enabled}），且<b>不可物理删除</b>（历史凭证引用必须永远可追溯）。
 *
 * <h2>预置科目守门（CLAUDE.md 原则 2：账表勾稽口径稳定）</h2>
 * 预置科目（{@link #isPreset}，走 V19 迁移 INSERT）<b>不可停用、不可改类别</b>——本聚合
 * {@link #disable} 对预置科目直接拒绝；类别本就 final 不提供修改方法。
 */
public final class Account implements AuditTarget {

    private static final int CODE_MAX_LENGTH = 32;
    private static final int NAME_MAX_LENGTH = 100;

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 科目编码（租户内唯一，如 1001 / 222101） */
    private final String code;

    private final String name;

    /** 科目类别（建档后不可改；预置科目尤甚） */
    private final AccountType type;

    /** 余额方向（借 / 贷） */
    private final BalanceDirection balanceDir;

    /** 上级科目编码（树形自关联，按编码；一级科目为 null） */
    private final String parentCode;

    /** 科目层级（一级=1，二级=2，……） */
    private final int level;

    /** 是否末级科目（仅末级可挂凭证行） */
    private final boolean isLeaf;

    /** 是否启用（停用后新凭证行不得引用，历史不受影响） */
    private boolean enabled;

    /** 是否预置科目（V19 迁移 INSERT，禁停用 / 改类别） */
    private final boolean isPreset;

    private final String createdBy;
    private final Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;

    private Account(Long id, String code, String name, AccountType type, BalanceDirection balanceDir,
                    String parentCode, int level, boolean isLeaf, boolean enabled, boolean isPreset,
                    String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.type = type;
        this.balanceDir = balanceDir;
        this.parentCode = parentCode;
        this.level = level;
        this.isLeaf = isLeaf;
        this.enabled = enabled;
        this.isPreset = isPreset;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    /**
     * 新建科目（启用、非预置）。
     *
     * @param code       科目编码（非空，≤32 字符，租户内唯一由 {@link AccountService} 把关）
     * @param name       科目名称（非空，≤100 字符）
     * @param type       科目类别
     * @param balanceDir 余额方向
     * @param parentCode 上级科目编码（一级科目传 null）
     * @param level      科目层级（≥1，由 {@link AccountService} 按 parent 推算）
     * @param isLeaf     是否末级（仅末级可挂账）
     * @param operator   操作人
     */
    public static Account create(String code, String name, AccountType type, BalanceDirection balanceDir,
                                 String parentCode, int level, boolean isLeaf, String operator) {
        String normalizedCode = validateCode(code);
        String normalizedName = validateName(name);
        Objects.requireNonNull(type, "科目类别不能为空");
        Objects.requireNonNull(balanceDir, "余额方向不能为空");
        if (level < 1) {
            throw new IllegalArgumentException("科目层级必须 >= 1: " + level);
        }
        String normalizedParent = (parentCode == null || parentCode.isBlank()) ? null : parentCode.strip();
        Objects.requireNonNull(operator, "operator 不能为空");
        Instant now = Instant.now();
        return new Account(null, normalizedCode, normalizedName, type, balanceDir, normalizedParent,
                level, isLeaf, true, false, operator, now, operator, now);
    }

    /** 持久层重建工厂（不重跑业务校验，库中数据以入库时校验为准） */
    public static Account restore(long id, String code, String name, AccountType type,
                                  BalanceDirection balanceDir, String parentCode, int level, boolean isLeaf,
                                  boolean enabled, boolean isPreset, String createdBy, Instant createdAt,
                                  String updatedBy, Instant updatedAt) {
        return new Account(id, code, name, type, balanceDir, parentCode, level, isLeaf, enabled, isPreset,
                createdBy, createdAt, updatedBy, updatedAt);
    }

    /** 启用：仅停用状态可启用（重复启用视为误操作，直接拒绝） */
    public void enable(String operator) {
        if (enabled) {
            throw new IllegalArgumentException("科目[" + code + "] 已是启用状态，无需重复启用");
        }
        this.enabled = true;
        touch(operator);
    }

    /**
     * 停用：仅启用状态可停用；预置科目禁止停用（守门保证账表勾稽口径稳定）。
     * 停用后新凭证行不得引用，历史凭证不受影响（档案不可物理删除）。
     */
    public void disable(String operator) {
        if (isPreset) {
            throw new IllegalArgumentException("预置科目[" + code + "] 不可停用");
        }
        if (!enabled) {
            throw new IllegalArgumentException("科目[" + code + "] 已是停用状态，无需重复停用");
        }
        this.enabled = false;
        touch(operator);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("科目 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    private void touch(String operator) {
        this.updatedBy = Objects.requireNonNull(operator, "operator 不能为空");
        this.updatedAt = Instant.now();
    }

    // ---------------------------------------------------------------
    // 校验
    // ---------------------------------------------------------------

    private static String validateCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("科目编码不能为空");
        }
        String trimmed = code.strip();
        if (trimmed.length() > CODE_MAX_LENGTH) {
            throw new IllegalArgumentException("科目编码不能超过 " + CODE_MAX_LENGTH + " 个字符");
        }
        return trimmed;
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("科目名称不能为空");
        }
        String trimmed = name.strip();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("科目名称不能超过 " + NAME_MAX_LENGTH + " 个字符");
        }
        return trimmed;
    }

    // ---------------------------------------------------------------
    // 只读访问器
    // ---------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public AccountType getType() {
        return type;
    }

    public BalanceDirection getBalanceDir() {
        return balanceDir;
    }

    public String getParentCode() {
        return parentCode;
    }

    public int getLevel() {
        return level;
    }

    public boolean isLeaf() {
        return isLeaf;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isPreset() {
        return isPreset;
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

    // ---------------------------------------------------------------
    // AuditTarget（M2-T07）
    // ---------------------------------------------------------------

    @Override
    public Long auditTargetId() {
        return id;
    }

    @Override
    public String auditTargetCode() {
        return code;
    }

    @Override
    public String auditSummary() {
        return "编码=" + AuditTarget.text(code) + ", 名称=" + AuditTarget.text(name)
                + ", 类别=" + type.label() + ", 方向=" + balanceDir.label()
                + ", 上级=" + AuditTarget.text(parentCode) + ", 层级=" + level
                + ", 末级=" + isLeaf + ", 状态=" + (enabled ? "启用" : "停用")
                + ", 预置=" + isPreset;
    }
}
