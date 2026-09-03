package com.sjherp.domain.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.ArchiveStatus;

/**
 * 用户领域服务测试（M2-T05）：创建/改密/重置/分配角色/启停 + 密码强度规则 + 登录认证。
 */
class UserServiceTest {

    private static final String OPERATOR = "tester";
    private static final String GOOD_PASSWORD = "Passw0rd2026";

    private InMemoryUserRepository repository;
    private UserService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryUserRepository();
        service = new UserService(repository, new FakePasswordHasher());
    }

    private User createUser(String username) {
        return service.create(username, "测试用户", GOOD_PASSWORD, Set.of(Role.SALES), OPERATOR);
    }

    // ---------------------------------------------------------------- 创建

    @Test
    void 创建用户_密码哈希存储_明文不落库_审计字段完整() {
        User user = createUser("zhangsan");
        assertNotNull(user.getId());
        assertEquals("zhangsan", user.getUsername());
        assertEquals(ArchiveStatus.ENABLED, user.getStatus());
        assertEquals(Set.of(Role.SALES), user.getRoles());
        // 哈希存储：库中不出现明文
        assertNotEquals(GOOD_PASSWORD, user.getPasswordHash());
        assertTrue(user.getPasswordHash().startsWith("fake-hash:"));
        assertEquals(OPERATOR, user.getCreatedBy());
        assertNotNull(user.getCreatedAt());
        assertEquals(OPERATOR, user.getUpdatedBy());
    }

    @Test
    void 登录名重复被拒绝() {
        createUser("zhangsan");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> createUser("zhangsan"));
        assertTrue(e.getMessage().contains("已存在"));
    }

    @Test
    void 登录名非法被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> createUser(null));
        assertThrows(IllegalArgumentException.class, () -> createUser("  "));
        assertThrows(IllegalArgumentException.class, () -> createUser("张 三")); // 含空格/非法字符
        assertThrows(IllegalArgumentException.class, () -> createUser("a")); // 过短
    }

    @Test
    void 角色为空被拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("zhangsan", "张三", GOOD_PASSWORD, Set.of(), OPERATOR));
        assertThrows(IllegalArgumentException.class,
                () -> service.create("zhangsan", "张三", GOOD_PASSWORD, null, OPERATOR));
    }

    // ---------------------------------------------------------------- 密码强度

    @Test
    void 密码强度_不足8位被拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("zhangsan", "张三", "a1b2c3", Set.of(Role.SALES), OPERATOR));
    }

    @Test
    void 密码强度_纯字母或纯数字被拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("zhangsan", "张三", "abcdefgh", Set.of(Role.SALES), OPERATOR));
        assertThrows(IllegalArgumentException.class,
                () -> service.create("zhangsan", "张三", "12345678", Set.of(Role.SALES), OPERATOR));
        assertThrows(IllegalArgumentException.class,
                () -> service.create("zhangsan", "张三", null, Set.of(Role.SALES), OPERATOR));
    }

    @Test
    void 密码强度_字母数字混合且含特殊字符放行() {
        String validPassword = "Test@Pass123";
        assertEquals(validPassword.length() >= 8, true);
        User user = service.create("zhangsan", "张三", validPassword, Set.of(Role.SALES), OPERATOR);
        assertNotNull(user.getId());
    }

    @Test
    void 密码强度_超长被拒绝() {
        String tooLong = "a1".repeat(40); // 80 位
        assertThrows(IllegalArgumentException.class,
                () -> service.create("zhangsan", "张三", tooLong, Set.of(Role.SALES), OPERATOR));
    }

    // ---------------------------------------------------------------- 登录认证

    @Test
    void 认证_密码正确放行() {
        createUser("zhangsan");
        User user = service.authenticate("zhangsan", GOOD_PASSWORD);
        assertEquals("zhangsan", user.getUsername());
    }

    @Test
    void 认证_密码错误与用户不存在统一文案() {
        createUser("zhangsan");
        AuthenticationFailedException wrongPassword = assertThrows(AuthenticationFailedException.class,
                () -> service.authenticate("zhangsan", "Wrong0000"));
        AuthenticationFailedException noUser = assertThrows(AuthenticationFailedException.class,
                () -> service.authenticate("nobody", GOOD_PASSWORD));
        // 不泄露登录名是否存在：两种失败的文案一致
        assertEquals(wrongPassword.getMessage(), noUser.getMessage());
    }

    @Test
    void 认证_停用账号被拒绝() {
        User user = createUser("zhangsan");
        service.disable(user.getId(), OPERATOR);
        AuthenticationFailedException e = assertThrows(AuthenticationFailedException.class,
                () -> service.authenticate("zhangsan", GOOD_PASSWORD));
        assertTrue(e.getMessage().contains("停用"));
    }

    // ---------------------------------------------------------------- 改密 / 重置

    @Test
    void 本人改密_旧密码正确才放行_新密码过强度校验() {
        User user = createUser("zhangsan");
        long id = user.getId();

        // 旧密码错误 → 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> service.changePassword(id, "Wrong0000", "NewPass2026", OPERATOR));
        // 新密码太弱 → 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> service.changePassword(id, GOOD_PASSWORD, "abcdefgh", OPERATOR));

        service.changePassword(id, GOOD_PASSWORD, "NewPass2026", "zhangsan");
        // 新密码可登录，旧密码失效
        assertNotNull(service.authenticate("zhangsan", "NewPass2026"));
        assertThrows(AuthenticationFailedException.class,
                () -> service.authenticate("zhangsan", GOOD_PASSWORD));
    }

    @Test
    void 管理员重置密码_无需旧密码() {
        User user = createUser("zhangsan");
        service.resetPassword(user.getId(), "Reset2026ok", "admin");
        assertNotNull(service.authenticate("zhangsan", "Reset2026ok"));
        assertEquals("admin", service.get(user.getId()).getUpdatedBy());
    }

    // ---------------------------------------------------------------- 角色 / 启停

    @Test
    void 分配角色_整体替换_空集合被拒绝() {
        User user = createUser("zhangsan");
        service.assignRoles(user.getId(), Set.of(Role.ACCOUNTANT, Role.BOSS), OPERATOR);
        assertEquals(Set.of(Role.ACCOUNTANT, Role.BOSS), service.get(user.getId()).getRoles());
        assertThrows(IllegalArgumentException.class,
                () -> service.assignRoles(user.getId(), Set.of(), OPERATOR));
    }

    @Test
    void 启停规则_重复操作被拒绝() {
        User user = createUser("zhangsan");
        long id = user.getId();

        User disabled = service.disable(id, "admin");
        assertEquals(ArchiveStatus.DISABLED, disabled.getStatus());
        assertEquals("admin", disabled.getUpdatedBy());
        assertThrows(IllegalArgumentException.class, () -> service.disable(id, "admin"));

        User enabled = service.enable(id, OPERATOR);
        assertEquals(ArchiveStatus.ENABLED, enabled.getStatus());
        assertThrows(IllegalArgumentException.class, () -> service.enable(id, OPERATOR));
    }

    @Test
    void 查询不存在的用户抛404异常() {
        assertThrows(UserNotFoundException.class, () -> service.get(999L));
    }

    @Test
    void 全量列表按id升序() {
        createUser("zhangsan");
        createUser("lisi");
        List<User> users = service.list();
        assertEquals(2, users.size());
        assertEquals("zhangsan", users.get(0).getUsername());
        assertEquals("lisi", users.get(1).getUsername());
    }

    // ---------------------------------------------------------------- 测试替身

    /** 可逆校验的假哈希器（仅测试使用；生产实现为 infra 的 BCrypt） */
    static final class FakePasswordHasher implements PasswordHasher {
        @Override
        public String hash(String rawPassword) {
            return "fake-hash:" + rawPassword;
        }

        @Override
        public boolean matches(String rawPassword, String passwordHash) {
            return ("fake-hash:" + rawPassword).equals(passwordHash);
        }
    }

    /** identity 领域测试用内存仓储替身（仅测试使用，不进生产） */
    static final class InMemoryUserRepository implements UserRepository {
        final Map<Long, User> store = new LinkedHashMap<>();
        private final AtomicLong idGen = new AtomicLong();

        @Override
        public void save(User user) {
            if (user.getId() == null) {
                user.assignId(idGen.incrementAndGet());
            }
            store.put(user.getId(), user);
        }

        @Override
        public Optional<User> findById(long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Optional<User> findByUsername(String username) {
            return store.values().stream()
                    .filter(u -> u.getUsername().equals(username))
                    .findFirst();
        }

        @Override
        public boolean existsByUsername(String username) {
            return findByUsername(username).isPresent();
        }

        @Override
        public List<User> findAll() {
            return List.copyOf(store.values());
        }
    }
}
