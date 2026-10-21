package com.sjherp.domain.identity;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.sjherp.domain.common.audit.Audited;

/**
 * 用户领域服务（所有用户写操作的唯一入口，CLAUDE.md 原则 1）。
 *
 * <p>业务规则：
 * <ul>
 *   <li>登录名唯一（仓储查重 + 数据库 (tenant_id, username) 联合唯一键兜底）；</li>
 *   <li>密码强度：见 {@link PasswordPolicy}（至少 {@value PasswordPolicy#MIN_LENGTH} 位，
 *       须同时含大写、小写与数字；最长 {@value PasswordPolicy#MAX_LENGTH} 位）；</li>
 *   <li>明文密码只在本服务内瞬时存在，经 {@link PasswordHasher} 哈希后存储；</li>
 *   <li>认证失败统一报"用户名或密码错误"（不泄露登录名是否存在），停用账号拒绝登录；</li>
 *   <li>启停规则：重复启用/停用直接拒绝（约定同档案，见 {@link User}）。</li>
 * </ul>
 */
public class UserService {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;

    public UserService(UserRepository userRepository, PasswordHasher passwordHasher) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository 不能为空");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher 不能为空");
    }

    /** 创建用户（登录名唯一、密码过强度校验后哈希存储），落库后回填 id */
    @Audited(action = "user.create", targetType = "user")
    public User create(String username, String displayName, String rawPassword,
                       Set<Role> roles, String operator) {
        String trimmed = username == null ? null : username.strip();
        PasswordPolicy.validate(rawPassword, trimmed);
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

    /** 本人改密：须核对旧密码，新密码过强度校验（审计只记动作，绝不落任何密码信息） */
    @Audited(action = "user.change_password", targetType = "user")
    public User changePassword(long id, String oldRawPassword, String newRawPassword, String operator) {
        User user = get(id);
        if (oldRawPassword == null || !passwordHasher.matches(oldRawPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("原密码不正确");
        }
        PasswordPolicy.validate(newRawPassword, user.getUsername());
        user.changePasswordHash(passwordHasher.hash(newRawPassword), operator);
        userRepository.save(user);
        return user;
    }

    /** 管理员重置密码：无需旧密码（调用方需限定 ADMIN 角色），新密码过强度校验 */
    @Audited(action = "user.reset_password", targetType = "user")
    public User resetPassword(long id, String newRawPassword, String operator) {
        User user = get(id);
        PasswordPolicy.validate(newRawPassword, user.getUsername());
        user.changePasswordHash(passwordHasher.hash(newRawPassword), operator);
        userRepository.save(user);
        return user;
    }

    /** 整体替换角色集合（至少一个角色） */
    @Audited(action = "user.assign_roles", targetType = "user")
    public User assignRoles(long id, Set<Role> roles, String operator) {
        User user = get(id);
        user.assignRoles(roles, operator);
        userRepository.save(user);
        return user;
    }

    /** 启用用户 */
    @Audited(action = "user.enable", targetType = "user")
    public User enable(long id, String operator) {
        User user = get(id);
        user.enable(operator);
        userRepository.save(user);
        return user;
    }

    /** 停用用户（停用后立即不可登录，已签发的 JWT 在过滤器逐请求校验时失效） */
    @Audited(action = "user.disable", targetType = "user")
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

}
