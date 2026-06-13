package com.sjherp.app.fund;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
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
import com.sjherp.app.security.PermissionGuard;
import com.sjherp.app.security.SecurityConfig;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.fund.PaymentAccount;
import com.sjherp.domain.fund.PaymentAccountCommand;
import com.sjherp.domain.fund.PaymentAccountQuery;
import com.sjherp.domain.fund.PaymentAccountService;
import com.sjherp.domain.fund.PaymentAccountType;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;

/**
 * 资金账户 API 权限测试（M4-T04a，MockMvc + 真实 SecurityConfig，照
 * {@code SettlementApiPermissionTest} / {@code ArchiveWritePermissionApiTest} 范式）：
 * <ul>
 *   <li><b>写</b>（建/更新/启/停）须 {@code finance:payment_account}（ADMIN/BOSS/ACCOUNTANT 放行）；</li>
 *   <li>无权角色（SALES/WAREHOUSE/PURCHASER）写 → 403 统一文案；未登录写 → 401；</li>
 *   <li><b>查询</b>登录即可（任意角色 200），未登录查 → 401。</li>
 * </ul>
 *
 * <p>装配口径同 {@code SettlementApiPermissionTest}：{@code @Import({SecurityConfig.class, PermissionGuard.class})}
 * （{@code @perm.has(...)} 依赖 perm bean），用 {@code authentication()} 直接注入认证态。
 */
@WebMvcTest(controllers = PaymentAccountController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class})
class PaymentAccountApiPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentAccountService paymentAccountService;
    /** JWT 过滤器装配依赖（本测试用 authentication() 直接注入认证态，不走 token 解析） */
    @MockitoBean
    private UserRepository userRepository;

    private static final String ACCOUNT_JSON = """
            {"name":"工行基本户","accountType":"BANK","glAccountCode":"1002"}""";

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        PaymentAccount saved = PaymentAccount.restore(1L, "FA-202606-0001", "工行基本户",
                PaymentAccountType.BANK, "1002", null, null, ArchiveStatus.ENABLED,
                "tester", now, "tester", now);
        Mockito.when(paymentAccountService.create(Mockito.any(PaymentAccountCommand.class), Mockito.anyString()))
                .thenReturn(saved);
        Mockito.when(paymentAccountService.search(Mockito.any(PaymentAccountQuery.class)))
                .thenReturn(new PageResult<>(java.util.List.of(), 0L, 1, 20));
    }

    /** 构造与 JWT 过滤器同构的认证态：principal=AuthenticatedUser，权限=ROLE_角色名 */
    private static RequestPostProcessor asUser(Role... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "tester", "测试用户", Set.of(roles));
        var authorities = Set.of(roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    // ---------------------------------------------------------------- 写：权限内放行 201

    @Test
    void 会计建资金账户_201() throws Exception {
        mockMvc.perform(post("/api/fund/accounts").with(asUser(Role.ACCOUNTANT))
                        .contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("FA-202606-0001"));
    }

    @Test
    void 老板建资金账户_201() throws Exception {
        mockMvc.perform(post("/api/fund/accounts").with(asUser(Role.BOSS))
                        .contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_JSON))
                .andExpect(status().isCreated());
    }

    @Test
    void 管理员建资金账户_201() throws Exception {
        mockMvc.perform(post("/api/fund/accounts").with(asUser(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_JSON))
                .andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------- 写：越权 403

    @Test
    void 销售建资金账户_403_统一文案() throws Exception {
        mockMvc.perform(post("/api/fund/accounts").with(asUser(Role.SALES))
                        .contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(paymentAccountService);
    }

    @Test
    void 仓管建资金账户_403() throws Exception {
        mockMvc.perform(post("/api/fund/accounts").with(asUser(Role.WAREHOUSE))
                        .contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_JSON))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(paymentAccountService);
    }

    @Test
    void 采购建资金账户_403() throws Exception {
        mockMvc.perform(post("/api/fund/accounts").with(asUser(Role.PURCHASER))
                        .contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_JSON))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(paymentAccountService);
    }

    @Test
    void 销售停用资金账户_403() throws Exception {
        mockMvc.perform(post("/api/fund/accounts/1/disable").with(asUser(Role.SALES)))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(paymentAccountService);
    }

    // ---------------------------------------------------------------- 查询：登录即可

    @Test
    void 销售查资金账户列表_200_登录即可() throws Exception {
        mockMvc.perform(get("/api/fund/accounts").with(asUser(Role.SALES)))
                .andExpect(status().isOk());
    }

    @Test
    void 仓管查资金账户列表_200_登录即可() throws Exception {
        mockMvc.perform(get("/api/fund/accounts").with(asUser(Role.WAREHOUSE)))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- 未登录 401

    @Test
    void 未登录建资金账户_401() throws Exception {
        mockMvc.perform(post("/api/fund/accounts")
                        .contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    @Test
    void 未登录查资金账户列表_401() throws Exception {
        mockMvc.perform(get("/api/fund/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }
}
