package com.sjherp.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.User;
import com.sjherp.domain.identity.UserRepository;

/**
 * RolePermissionToolChecker 单测（M2-T06）：按 ToolContext.userId 查角色 →
 * RolePermissions 矩阵判定；用户不存在/停用/userId 非法/查库异常一律拒绝（宁拒勿放）。
 */
class RolePermissionToolCheckerTest {

    private UserRepository userRepository;
    private RolePermissionToolChecker checker;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        checker = new RolePermissionToolChecker(userRepository);
    }

    /** 声明了指定权限点的假工具 */
    private static Tool toolRequiring(String permission) {
        return new Tool() {
            @Override
            public String name() {
                return "create_customer";
            }

            @Override
            public String description() {
                return "测试用工具";
            }

            @Override
            public String parameterSchema() {
                return "{\"type\":\"object\"}";
            }

            @Override
            public String requiredPermission() {
                return permission;
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
                return ToolResult.ok(Map.of());
            }
        };
    }

    private static User userWithRoles(Role... roles) {
        return User.restore(7L, "tester", "测试用户", "$2a$10$hash", Set.of(roles),
                com.sjherp.domain.common.ArchiveStatus.ENABLED,
                "system", java.time.Instant.now(), "system", java.time.Instant.now());
    }

    private static ToolContext contextOfUser(String userId) {
        return new ToolContext("session-1", userId, "帮我新建客户");
    }

    @Test
    void 仓管角色调用创建客户工具_被拒() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(userWithRoles(Role.WAREHOUSE)));
        assertThat(checker.isAllowed(toolRequiring("partner:create_customer"), contextOfUser("7"))).isFalse();
    }

    @Test
    void 仓管角色调用创建仓库工具_放行() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(userWithRoles(Role.WAREHOUSE)));
        assertThat(checker.isAllowed(toolRequiring("warehouse:create_warehouse"), contextOfUser("7"))).isTrue();
    }

    @Test
    void 管理员调用创建客户工具_放行() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(userWithRoles(Role.ADMIN)));
        assertThat(checker.isAllowed(toolRequiring("partner:create_customer"), contextOfUser("7"))).isTrue();
    }

    @Test
    void 用户不存在_拒绝() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThat(checker.isAllowed(toolRequiring("partner:create_customer"), contextOfUser("99"))).isFalse();
    }

    @Test
    void 用户已停用_拒绝() {
        User disabled = userWithRoles(Role.ADMIN);
        disabled.disable("system");
        when(userRepository.findById(7L)).thenReturn(Optional.of(disabled));
        assertThat(checker.isAllowed(toolRequiring("partner:create_customer"), contextOfUser("7"))).isFalse();
    }

    @Test
    void userId缺失或非法_拒绝且不查库() {
        Tool tool = toolRequiring("partner:create_customer");
        assertThat(checker.isAllowed(tool, contextOfUser(null))).isFalse();
        assertThat(checker.isAllowed(tool, contextOfUser("  "))).isFalse();
        assertThat(checker.isAllowed(tool, contextOfUser("not-a-number"))).isFalse();
        assertThat(checker.isAllowed(tool, null)).isFalse();
        verifyNoInteractions(userRepository);
    }

    @Test
    void 未知权限点_即使管理员也拒绝() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(userWithRoles(Role.ADMIN)));
        assertThat(checker.isAllowed(toolRequiring("no:such_permission"), contextOfUser("7"))).isFalse();
    }

    @Test
    void 工具未声明权限点_直接放行不查库() {
        assertThat(checker.isAllowed(toolRequiring(null), contextOfUser("7"))).isTrue();
        verifyNoInteractions(userRepository);
    }

    @Test
    void 查库异常_按拒绝处理() {
        when(userRepository.findById(7L)).thenThrow(new RuntimeException("数据库连接失败"));
        assertThat(checker.isAllowed(toolRequiring("partner:create_customer"), contextOfUser("7"))).isFalse();
    }
}
