package com.sjherp.app.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.infra.persistence.audit.AuditLogRepository;

/**
 * 审计日志查询 API 权限测试（M2-T07，MockMvc + 真实 SecurityConfig）：
 * 仅 ADMIN/BOSS 可查（审计数据含全员操作轨迹）；其余角色 403 统一文案；未登录 401；
 * 参数非法 400 {"error"}。
 */
@WebMvcTest(controllers = AuditLogController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import(SecurityConfig.class)
class AuditLogApiPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLogRepository auditLogRepository;
    /** JWT 过滤器装配依赖（本测试用 authentication() 直接注入认证态，不走 token 解析） */
    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        Mockito.when(auditLogRepository.search(any()))
                .thenReturn(new PageResult<>(List.of(), 0, 1, 20));
    }

    /** 构造与 JWT 过滤器同构的认证态：principal=AuthenticatedUser，权限=ROLE_角色名 */
    private static RequestPostProcessor asUser(Role... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "tester", "测试用户", Set.of(roles));
        var authorities = Set.of(roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    @Test
    void 销售查审计日志_403_统一文案() throws Exception {
        mockMvc.perform(get("/api/audit-logs").with(asUser(Role.SALES)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(auditLogRepository);
    }

    @Test
    void 仓管查审计日志_403() throws Exception {
        mockMvc.perform(get("/api/audit-logs").with(asUser(Role.WAREHOUSE)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 管理员查审计日志_200() throws Exception {
        mockMvc.perform(get("/api/audit-logs").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void 老板查审计日志_200_带筛选参数() throws Exception {
        mockMvc.perform(get("/api/audit-logs")
                        .param("operator", "agent:1")
                        .param("action", "customer.create")
                        .param("from", "2026-06-12T00:00:00Z")
                        .with(asUser(Role.BOSS)))
                .andExpect(status().isOk());
    }

    @Test
    void 时间参数非法_400() throws Exception {
        mockMvc.perform(get("/api/audit-logs").param("from", "2026-06-12")
                        .with(asUser(Role.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 未登录_401() throws Exception {
        mockMvc.perform(get("/api/audit-logs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }
}
