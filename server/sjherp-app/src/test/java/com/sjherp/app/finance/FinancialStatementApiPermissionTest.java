package com.sjherp.app.finance;

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

import com.sjherp.app.finance.FinancialStatementDtos.BalanceSheet;
import com.sjherp.app.finance.FinancialStatementDtos.IncomeStatement;
import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.app.security.PermissionGuard;
import com.sjherp.app.security.SecurityConfig;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;

/**
 * 财务报表 API 权限测试（M4-T06，MockMvc + 真实 SecurityConfig）：
 * 两端点（balance-sheet/income-statement）均须 {@code finance:report}（ADMIN/BOSS/ACCOUNTANT 放行）；
 * 其余角色 403 统一文案；未登录 401。装配口径同 {@code AgingReportApiPermissionTest}。
 */
@WebMvcTest(controllers = FinancialStatementController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class})
class FinancialStatementApiPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FinancialStatementService service;
    /** JWT 过滤器装配依赖（本测试用 authentication() 直接注入认证态，不走 token 解析）。 */
    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        BalanceSheet bs = new BalanceSheet("202606",
                List.of(), "0", List.of(), "0", List.of(), "0", true);
        IncomeStatement is = new IncomeStatement("202606", List.of(), "0", "0");
        Mockito.when(service.balanceSheet(Mockito.anyString())).thenReturn(bs);
        Mockito.when(service.incomeStatement(Mockito.anyString())).thenReturn(is);
    }

    /** 构造与 JWT 过滤器同构的认证态：principal=AuthenticatedUser，权限=ROLE_角色名。 */
    private static RequestPostProcessor asUser(Role... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "tester", "测试用户", Set.of(roles));
        var authorities = Set.of(roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    // ---------------------------------------------------------------- 放行 200

    @Test
    void 会计查资产负债表_200() throws Exception {
        mockMvc.perform(get("/api/reports/balance-sheet?period=202606").with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(true));
    }

    @Test
    void 老板查利润表_200() throws Exception {
        mockMvc.perform(get("/api/reports/income-statement?period=202606").with(asUser(Role.BOSS)))
                .andExpect(status().isOk());
    }

    @Test
    void 管理员查资产负债表_200() throws Exception {
        mockMvc.perform(get("/api/reports/balance-sheet?period=202606").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- 越权 403

    @Test
    void 销售查资产负债表_403_统一文案() throws Exception {
        mockMvc.perform(get("/api/reports/balance-sheet?period=202606").with(asUser(Role.SALES)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(service);
    }

    @Test
    void 仓管查利润表_403() throws Exception {
        mockMvc.perform(get("/api/reports/income-statement?period=202606").with(asUser(Role.WAREHOUSE)))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(service);
    }

    @Test
    void 采购查利润表_403() throws Exception {
        mockMvc.perform(get("/api/reports/income-statement?period=202606").with(asUser(Role.PURCHASER)))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(service);
    }

    // ---------------------------------------------------------------- 未登录 401

    @Test
    void 未登录查资产负债表_401() throws Exception {
        mockMvc.perform(get("/api/reports/balance-sheet?period=202606"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    @Test
    void 未登录查利润表_401() throws Exception {
        mockMvc.perform(get("/api/reports/income-statement?period=202606"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }
}
