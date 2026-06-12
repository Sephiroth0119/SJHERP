package com.sjherp.domain.identity;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.sjherp.domain.common.ArchiveStatus;

/**
 * 用户聚合根（M2-T05，模式样板：{@code Product}）。
 *
 * <p>用户是档案而非单据：没有状态机流转，只有启用/停用两态
 * （{@link ArchiveStatus}），**不可物理删除**（审计字段 created_by/updated_by
 * 以及会话归属必须永远可追溯到人），离职即停用。
 *
 * <p>passwordHash 对领域逻辑不透明：领域层只负责存储与传递，
 * 强度校验在 {@link UserService}（针对明文），哈希/比对经
 * {@link PasswordHasher} 端口由 infra 实现（BCrypt）。
 */
public final class User {

    private static final int USERNAME_MAX_LENGTH = 50;
    private static final int DISPLAY_NAME_MAX_LENGTH = 50;

    /** 登录名字符集：字母/数字/点/下划线/连字符（避免空白与控制字符混入登录名） */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{2,50}");

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 登录名（租户内唯一，创建后不可改——审计记录以 username 标识操作人） */
    private final String username;

    /** 显示名（界面展示用，可改） */
    private String displayName;

    /** 密码哈希（BCrypt，领域层不透明存储，绝不出现明文） */
    private String passwordHash;

    /** 角色集合（至少一个） */
    private Set<Role> roles;

    private ArchiveStatus status;

    private final String createdBy;
    private final Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;

    /** 新建用户，初始状态为启用（id 由仓储落库后回填） */
    public User(String username, String displayName, String passwordHash, Set<Role> roles, String operator) {
        this.username = validateUsername(username);
        this.displayName = validateDisplayName(displayName);
        this.passwordHash = validatePasswordHash(passwordHash);
        this.roles = validateRoles(roles);
        this.status = ArchiveStatus.ENABLED;
        this.createdBy = Objects.requireNonNull(operator, "operator 不能为空");
        this.createdAt = Instant.now();
        this.updatedBy = operator;
        this.updatedAt = this.createdAt;
    }

    private User(Long id, String username, String displayName, String passwordHash, Set<Role> roles,
                 ArchiveStatus status, String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.roles = EnumSet.copyOf(roles);
        this.status = status;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    /** 持久层重建工厂（不重跑业务校验，库中数据以入库时校验为准） */
    public static User restore(long id, String username, String displayName, String passwordHash,
                               Set<Role> roles, ArchiveStatus status,
                               String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
        return new User(id, username, displayName, passwordHash, roles, status,
                createdBy, createdAt, updatedBy, updatedAt);
    }

    /** 修改显示名 */
    public void rename(String displayName, String operator) {
        this.displayName = validateDisplayName(displayName);
        touch(operator);
    }

    /** 更换密码哈希（明文强度校验与新旧密码核对在 {@link UserService}） */
    public void changePasswordHash(String passwordHash, String operator) {
        this.passwordHash = validatePasswordHash(passwordHash);
        touch(operator);
    }

    /** 整体替换角色集合（至少一个角色） */
    public void assignRoles(Set<Role> roles, String operator) {
        this.roles = validateRoles(roles);
        touch(operator);
    }

    /** 启用：仅停用状态可启用（重复启用视为误操作，直接拒绝，约定同档案） */
    public void enable(String operator) {
        if (status == ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("用户[" + username + "] 已是启用状态，无需重复启用");
        }
        this.status = ArchiveStatus.ENABLED;
        touch(operator);
    }

    /** 停用：仅启用状态可停用；停用后立即不可登录（JWT 过滤器逐请求校验状态） */
    public void disable(String operator) {
        if (status == ArchiveStatus.DISABLED) {
            throw new IllegalArgumentException("用户[" + username + "] 已是停用状态，无需重复停用");
        }
        this.status = ArchiveStatus.DISABLED;
        touch(operator);
    }

    /** 是否处于可登录状态 */
    public boolean isEnabled() {
        return status == ArchiveStatus.ENABLED;
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("用户 id 已分配，不可重复分配: " + this.id);
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

    private static String validateUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("登录名不能为空");
        }
        String trimmed = username.strip();
        if (trimmed.length() > USERNAME_MAX_LENGTH || !USERNAME_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("登录名仅支持 2-50 位字母、数字、点、下划线、连字符");
        }
        return trimmed;
    }

    private static String validateDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("显示名不能为空");
        }
        String trimmed = displayName.strip();
        if (trimmed.length() > DISPLAY_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("显示名不能超过 " + DISPLAY_NAME_MAX_LENGTH + " 个字符");
        }
        return trimmed;
    }

    private static String validatePasswordHash(String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("密码哈希不能为空");
        }
        return passwordHash;
    }

    private static Set<Role> validateRoles(Set<Role> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("用户至少要有一个角色");
        }
        return EnumSet.copyOf(roles);
    }

    // ---------------------------------------------------------------
    // 只读访问器
    // ---------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    /** 角色集合的防御性拷贝 */
    public Set<Role> getRoles() {
        return EnumSet.copyOf(roles);
    }

    public ArchiveStatus getStatus() {
        return status;
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
