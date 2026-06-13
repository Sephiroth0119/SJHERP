package com.sjherp.app.settlement;

import static org.mockito.ArgumentMatchers.anyLong;
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
import com.sjherp.app.security.PermissionGuard;
import com.sjherp.app.security.SecurityConfig;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;

/**
 * 核销记录查询 API 权限测试（M4-T03，MockMvc + 真实 SecurityConfig）：
 * {@code GET /api/settlements} 须 {@code finance:settlement}（ADMIN/BOSS/ACCOUNTANT 放行）；
 * 其余角色 403 统一文案；未登录 401。
 *
 * <p>装配口径同 {@code AuditLogApiPermissionTest}：{@code @Import({SecurityConfig.class, PermissionGuard.class})}
 * （{@code @perm.has(...)} 依赖 perm bean），用 {@code authentication()} 直接注入认证态。
 */
@WebMvcTest(controllers = SettlementController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class})
class SettlementApiPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SettlementReadAppService settlementReadAppService;
    /** JWT 过滤器装配依赖（本测试用 authentication() 直接注入认证态，不走 token 解析） */
    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        Mockito.when(settlementReadAppService.findReceivableSettlements(anyLong()))
                .thenReturn(List.of());
        Mockito.when(settlementReadAppService.findPayableSettlements(anyLong()))
                .thenReturn(List.of());
    }

    /** 构造与 JWT 过滤器同构的认证态：principal=AuthenticatedUser，权限=ROLE_角色名 */
    private static RequestPostProcessor asUser(Role... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "tester", "测试用户", Set.of(roles));
        var authorities = Set.of(roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    // ---------------------------------------------------------------- 放行 200

    @Test
    void 会计查核销记录_200() throws Exception {
        mockMvc.perform(get("/api/settlements?type=RECEIVABLE&targetId=1").with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void 老板查核销记录_200() throws Exception {
        mockMvc.perform(get("/api/settlements?type=PAYABLE&targetId=1").with(asUser(Role.BOSS)))
                .andExpect(status().isOk());
    }

    @Test
    void 管理员查核销记录_200() throws Exception {
        mockMvc.perform(get("/api/settlements?type=RECEIVABLE&targetId=1").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- 越权 403

    @Test
    void 销售查核销记录_403_统一文案() throws Exception {
        mockMvc.perform(get("/api/settlements?type=RECEIVABLE&targetId=1").with(asUser(Role.SALES)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(settlementReadAppService);
    }

    @Test
    void 仓管查核销记录_403() throws Exception {
        mockMvc.perform(get("/api/settlements?type=PAYABLE&targetId=1").with(asUser(Role.WAREHOUSE)))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(settlementReadAppService);
    }

    // ---------------------------------------------------------------- 未登录 401

    @Test
    void 未登录查核销记录_401() throws Exception {
        mockMvc.perform(get("/api/settlements?type=RECEIVABLE&targetId=1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }
}
