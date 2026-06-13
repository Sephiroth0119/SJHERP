package com.sjherp.app.finance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
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

import com.sjherp.app.finance.AgingReportDao.AgingGrandTotal;
import com.sjherp.app.finance.AgingReportDao.AgingReport;
import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.app.security.PermissionGuard;
import com.sjherp.app.security.SecurityConfig;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;

/**
 * 应收应付账龄 API 权限测试（M4-T03，MockMvc + 真实 SecurityConfig）：
 * 两端点均须 {@code finance:settlement}（ADMIN/BOSS/ACCOUNTANT 放行）；其余角色 403 统一文案；未登录 401。
 *
 * <p>装配口径同 {@code AuditLogApiPermissionTest}/{@code ArchiveWritePermissionApiTest}：
 * {@code @Import({SecurityConfig.class, PermissionGuard.class})}（{@code @perm.has(...)} 依赖 perm bean），
 * 用 {@code authentication()} 直接注入认证态（不走 JWT token 解析，但仍 @MockitoBean UserRepository 满足过滤器装配）。
 */
@WebMvcTest(controllers = AgingReportController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class})
class AgingReportApiPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgingReportDao agingReportDao;
    /** JWT 过滤器装配依赖（本测试用 authentication() 直接注入认证态，不走 token 解析） */
    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        AgingGrandTotal zero = new AgingGrandTotal(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        AgingReport empty = new AgingReport(LocalDate.of(2026, 6, 30),
                new PageResult<>(List.of(), 0L, 1, 20), zero);
        Mockito.when(agingReportDao.receivableAging(any(), any(), anyInt(), anyInt()))
                .thenReturn(empty);
        Mockito.when(agingReportDao.payableAging(any(), any(), anyInt(), anyInt()))
                .thenReturn(empty);
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
    void 会计查应收账龄_200() throws Exception {
        mockMvc.perform(get("/api/reports/receivable-aging").with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void 老板查应付账龄_200() throws Exception {
        mockMvc.perform(get("/api/reports/payable-aging").with(asUser(Role.BOSS)))
                .andExpect(status().isOk());
    }

    @Test
    void 管理员查应收账龄_200() throws Exception {
        mockMvc.perform(get("/api/reports/receivable-aging").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- 越权 403

    @Test
    void 销售查应收账龄_403_统一文案() throws Exception {
        mockMvc.perform(get("/api/reports/receivable-aging").with(asUser(Role.SALES)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(agingReportDao);
    }

    @Test
    void 仓管查应付账龄_403() throws Exception {
        mockMvc.perform(get("/api/reports/payable-aging").with(asUser(Role.WAREHOUSE)))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(agingReportDao);
    }

    // ---------------------------------------------------------------- 未登录 401

    @Test
    void 未登录查应收账龄_401() throws Exception {
        mockMvc.perform(get("/api/reports/receivable-aging"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    @Test
    void 未登录查应付账龄_401() throws Exception {
        mockMvc.perform(get("/api/reports/payable-aging"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }
}
