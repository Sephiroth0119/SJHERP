package com.sjherp.app.consistency;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.app.security.SecurityConfig;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;

/**
 * 数据一致性校验 API 权限测试（M3-T13，MockMvc + 真实 SecurityConfig）：
 * 仅 ADMIN/BOSS 可查（含全量账本差异）；其余角色 403 统一文案；未登录 401。
 */
@WebMvcTest(controllers = ConsistencyController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import(SecurityConfig.class)
class ConsistencyControllerPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConsistencyCheckService consistencyCheckService;
    /** JWT 过滤器装配依赖（本测试用 authentication() 直接注入认证态，不走 token 解析） */
    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        Mockito.when(consistencyCheckService.check())
                .thenReturn(new ConsistencyReport(Instant.now(), List.of()));
    }

    private static RequestPostProcessor asUser(Role... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "tester", "测试用户", Set.of(roles));
        var authorities = Set.of(roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    @Test
    void 管理员查一致性报告_200() throws Exception {
        mockMvc.perform(get("/api/consistency/check").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clean").value(true))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.breaks").isArray());
    }

    @Test
    void 老板查一致性报告_200() throws Exception {
        mockMvc.perform(get("/api/consistency/check").with(asUser(Role.BOSS)))
                .andExpect(status().isOk());
    }

    @Test
    void 销售查一致性报告_403_统一文案() throws Exception {
        mockMvc.perform(get("/api/consistency/check").with(asUser(Role.SALES)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(consistencyCheckService);
    }

    @Test
    void 仓管查一致性报告_403() throws Exception {
        mockMvc.perform(get("/api/consistency/check").with(asUser(Role.WAREHOUSE)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 未登录_401() throws Exception {
        mockMvc.perform(get("/api/consistency/check"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }
}
