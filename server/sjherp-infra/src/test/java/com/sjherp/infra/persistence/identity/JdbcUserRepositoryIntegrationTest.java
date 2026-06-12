package com.sjherp.infra.persistence.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.User;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

/**
 * 用户仓储真实 MySQL 最小往返测试（X-2）：insert → findById/findByUsername →
 * 角色 JSON 列更新后读回（roles JSON 数组列的编解码是该仓储的特有路径）。
 */
class JdbcUserRepositoryIntegrationTest extends MySqlContainerTestBase {

    private final JdbcUserRepository userRepository = new JdbcUserRepository(jdbc);

    @Test
    void 用户_保存后读回_角色JSON列更新生效() {
        String username = "it-user-" + uniqueSuffix();
        User user = new User(username, "集成测试用户",
                "$2a$10$abcdefghijklmnopqrstuvwxy", Set.of(Role.SALES), "tester");

        userRepository.save(user);

        assertThat(user.getId()).isNotNull();
        Optional<User> found = userRepository.findById(user.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getUsername()).isEqualTo(username);
        assertThat(found.get().getDisplayName()).isEqualTo("集成测试用户");
        assertThat(found.get().getRoles()).containsExactly(Role.SALES);
        assertThat(found.get().isEnabled()).isTrue();
        assertThat(userRepository.findByUsername(username)).isPresent();
        assertThat(userRepository.existsByUsername(username)).isTrue();

        // 更新路径：整体替换角色集合（JSON 数组列重写）后读回
        User loaded = found.get();
        loaded.assignRoles(Set.of(Role.SALES, Role.WAREHOUSE), "tester");
        userRepository.save(loaded);
        Optional<User> updated = userRepository.findById(user.getId());
        assertThat(updated).isPresent();
        assertThat(updated.get().getRoles())
                .containsExactlyInAnyOrder(Role.SALES, Role.WAREHOUSE);
    }

    @Test
    void 种子管理员_V6迁移已写入() {
        // V6 迁移内置 admin 种子用户（部署后必须改密）——迁移可执行性验证的一部分
        Optional<User> admin = userRepository.findByUsername("admin");
        assertThat(admin).isPresent();
        assertThat(admin.get().getRoles()).contains(Role.ADMIN);
    }
}
