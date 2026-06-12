package com.sjherp.app.identity;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.app.security.SecurityConfig;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.User;
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.domain.identity.UserService;

/**
 * 用户管理 API 的 ADMIN 角色限定测试（X-2 交叉校验盲区，类级 @PreAuthorize("hasRole('ADMIN')")）：
 * 非 ADMIN（含 BOSS）一律 403 且不触达服务层；未登录 401；ADMIN 放行且响应不含密码哈希。
 */
@WebMvcTest(controllers = UserAdminController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import(SecurityConfig.class)
class UserAdminControllerRoleTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    /** JWT 过滤器装配依赖（本测试用 authentication() 直接注入认证态，不走 token 解析） */
    @MockitoBean
    private UserRepository userRepository;

    private static final String CREATE_USER_JSON = """
            {"username":"newbie","displayName":"新人","password":"Passw0rd1","roles":["SALES"]}""";

    /** 构造与 JWT 过滤器同构的认证态（口径同 ArchiveWritePermissionApiTest） */
    private static RequestPostProcessor asUser(Role... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "tester", "测试用户", Set.of(roles));
        var authorities = Set.of(roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    @Test
    void 销售查看用户列表_403() throws Exception {
        mockMvc.perform(get("/api/identity/users").with(asUser(Role.SALES)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(userService);
    }

    @Test
    void 老板创建用户_非ADMIN同样403() throws Exception {
        // 用户管理仅 ADMIN——BOSS 也不放行（权限矩阵 M2-T06）
        mockMvc.perform(post("/api/identity/users").with(asUser(Role.BOSS))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_USER_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(userService);
    }

    @Test
    void 仓管停用用户_403() throws Exception {
        mockMvc.perform(post("/api/identity/users/1/disable").with(asUser(Role.WAREHOUSE)))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(userService);
    }

    @Test
    void 未登录_401() throws Exception {
        mockMvc.perform(get("/api/identity/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    @Test
    void 管理员查看用户列表_放行且不返回密码哈希() throws Exception {
        Mockito.when(userService.list()).thenReturn(List.of(
                User.restore(1L, "alice", "爱丽丝", "$2a$10$abcdefghijklmnopqrstuvwxy",
                        Set.of(Role.SALES), ArchiveStatus.ENABLED,
                        "tester", Instant.now(), "tester", Instant.now())));

        mockMvc.perform(get("/api/identity/users").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("alice"))
                .andExpect(jsonPath("$[0].roles[0]").value("SALES"))
                // 响应体绝不携带密码哈希（UserResponse 契约）
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }
}
