package com.sjherp.domain.identity;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 用户领域服务（所有用户写操作的唯一入口，CLAUDE.md 原则 1）。
 *
 * <p>业务规则：
 * <ul>
 *   <li>登录名唯一（仓储查重 + 数据库 (tenant_id, username) 联合唯一键兜底）；</li>
 *   <li>密码强度：至少 {@value #PASSWORD_MIN_LENGTH} 位且同时包含字母与数字
 *       （最长 {@value #PASSWORD_MAX_LENGTH} 位，BCrypt 72 字节上限内留余量）；</li>
 *   <li>明文密码只在本服务内瞬时存在，经 {@link PasswordHasher} 哈希后存储；</li>
 *   <li>认证失败统一报"用户名或密码错误"（不泄露登录名是否存在），停用账号拒绝登录；</li>
 *   <li>启停规则：重复启用/停用直接拒绝（约定同档案，见 {@link User}）。</li>
 * </ul>
 */
public class UserService {

    static final int PASSWORD_MIN_LENGTH = 8;
    static final int PASSWORD_MAX_LENGTH = 64;

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;

    public UserService(UserRepository userRepository, PasswordHasher passwordHasher) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository 不能为空");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher 不能为空");
    }

    /** 创建用户（登录名唯一、密码过强度校验后哈希存储），落库后回填 id */
    public User create(String username, String displayName, String rawPassword,
                       Set<Role> roles, String operator) {
        validatePasswordStrength(rawPassword);
        String trimmed = username == null ? null : username.strip();
        if (trimmed != null && userRepository.existsByUsername(trimmed)) {
            throw new IllegalArgumentException("登录名已存在: " + trimmed);
        }
        User user = new User(trimmed, displayName, passwordHasher.hash(rawPassword), roles, operator);
        userRepository.save(user);
        return user;
    }

    /**
     * 登录认证：登录名 + 明文密码。
     *
     * @throws AuthenticationFailedException 用户名不存在或密码不匹配（统一文案）、账号停用
     */
    public User authenticate(String username, String rawPassword) {
        User user = userRepository.findByUsername(username == null ? "" : username.strip())
                .orElseThrow(() -> new AuthenticationFailedException("用户名或密码错误"));
        if (rawPassword == null || !passwordHasher.matches(rawPassword, user.getPasswordHash())) {
            throw new AuthenticationFailedException("用户名或密码错误");
        }
        if (!user.isEnabled()) {
            throw new AuthenticationFailedException("账号已停用，请联系管理员");
        }
        return user;
    }

    /** 本人改密：须核对旧密码，新密码过强度校验 */
    public User changePassword(long id, String oldRawPassword, String newRawPassword, String operator) {
        User user = get(id);
        if (oldRawPassword == null || !passwordHasher.matches(oldRawPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("原密码不正确");
        }
        validatePasswordStrength(newRawPassword);
        user.changePasswordHash(passwordHasher.hash(newRawPassword), operator);
        userRepository.save(user);
        return user;
    }

    /** 管理员重置密码：无需旧密码（调用方需限定 ADMIN 角色），新密码过强度校验 */
    public User resetPassword(long id, String newRawPassword, String operator) {
        User user = get(id);
        validatePasswordStrength(newRawPassword);
        user.changePasswordHash(passwordHasher.hash(newRawPassword), operator);
        userRepository.save(user);
        return user;
    }

    /** 整体替换角色集合（至少一个角色） */
    public User assignRoles(long id, Set<Role> roles, String operator) {
        User user = get(id);
        user.assignRoles(roles, operator);
        userRepository.save(user);
        return user;
    }

    /** 启用用户 */
    public User enable(long id, String operator) {
        User user = get(id);
        user.enable(operator);
        userRepository.save(user);
        return user;
    }

    /** 停用用户（停用后立即不可登录，已签发的 JWT 在过滤器逐请求校验时失效） */
    public User disable(long id, String operator) {
        User user = get(id);
        user.disable(operator);
        userRepository.save(user);
        return user;
    }

    /** 按 id 查询（不存在抛 UserNotFoundException → API 404） */
    public User get(long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    /** 全量列表（管理界面用，小企业用户数量级很小） */
    public List<User> list() {
        return userRepository.findAll();
    }

    /**
     * 密码强度规则：至少 8 位，且同时包含字母与数字（M2-T05 验收口径；
     * 更复杂的策略——特殊字符、密码有效期——留 M8-T03 安全基线）。
     */
    static void validatePasswordStrength(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < PASSWORD_MIN_LENGTH) {
            throw new IllegalArgumentException("密码至少 " + PASSWORD_MIN_LENGTH + " 位，且须同时包含字母和数字");
        }
        if (rawPassword.length() > PASSWORD_MAX_LENGTH) {
            throw new IllegalArgumentException("密码不能超过 " + PASSWORD_MAX_LENGTH + " 位");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < rawPassword.length(); i++) {
            char c = rawPassword.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw new IllegalArgumentException("密码须同时包含字母和数字");
        }
    }
}
