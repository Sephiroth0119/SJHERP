package com.sjherp.app.payment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.app.security.PermissionGuard;
import com.sjherp.app.security.SecurityConfig;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.domain.payment.PaymentDisbursement;

/**
 * 付款单 API 权限测试（M4-T04b，MockMvc + 真实 SecurityConfig）：
 * {@code /api/payments} 写/查均须 {@code finance:settlement}（ADMIN/BOSS/ACCOUNTANT 放行）；
 * 其余角色 403 统一文案；未登录 401。
 *
 * <p>装配口径同 {@code SettlementApiPermissionTest} / {@code PaymentAccountApiPermissionTest}：
 * {@code @Import({SecurityConfig.class, PermissionGuard.class})}（{@code @perm.has(...)} 依赖 perm bean），
 * 用 {@code authentication()} 直接注入认证态。SecurityConfig 已 {@code csrf().disable()}，POST 无需 csrf 标记。
 */
@WebMvcTest(controllers = PaymentDisbursementController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class})
class PaymentDisbursementApiPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentDisbursementAppService paymentDisbursementAppService;
    /** JWT 过滤器装配依赖（本测试用 authentication() 直接注入认证态，不走 token 解析） */
    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        Mockito.when(paymentDisbursementAppService.search(any()))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 20));
        Mockito.when(paymentDisbursementAppService.post(anyString(), anyString()))
                .thenReturn(stubPosted());
        Mockito.when(paymentDisbursementAppService.create(anyLong(), anyLong(), any(), any(), any(),
                anyString())).thenReturn(stubPosted());
    }

    private static PaymentDisbursement stubPosted() {
        return PaymentDisbursement.create("PAYV-1", 1L, 10L, LocalDate.of(2026, 6, 14), null,
                List.of(com.sjherp.domain.payment.PaymentDisbursementLine.create(1, 100L,
                        new java.math.BigDecimal("100.00"))), "tester");
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
    void 会计查付款单列表_200() throws Exception {
        mockMvc.perform(get("/api/payments").with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void 老板查付款单列表_200() throws Exception {
        mockMvc.perform(get("/api/payments").with(asUser(Role.BOSS)))
                .andExpect(status().isOk());
    }

    @Test
    void 管理员查付款单列表_200() throws Exception {
        mockMvc.perform(get("/api/payments").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void 会计过账付款单_200() throws Exception {
        mockMvc.perform(post("/api/payments/PAYV-1/post").with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isOk());
        Mockito.verify(paymentDisbursementAppService).post("PAYV-1", "tester");
    }

    // ---------------------------------------------------------------- 越权 403

    @Test
    void 销售查付款单列表_403_统一文案() throws Exception {
        mockMvc.perform(get("/api/payments").with(asUser(Role.SALES)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(paymentDisbursementAppService);
    }

    @Test
    void 仓管过账付款单_403() throws Exception {
        mockMvc.perform(post("/api/payments/PAYV-1/post").with(asUser(Role.WAREHOUSE)))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(paymentDisbursementAppService);
    }

    @Test
    void 采购过账付款单_403() throws Exception {
        mockMvc.perform(post("/api/payments/PAYV-1/post").with(asUser(Role.PURCHASER)))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(paymentDisbursementAppService);
    }

    // ---------------------------------------------------------------- 未登录 401

    @Test
    void 未登录查付款单列表_401() throws Exception {
        mockMvc.perform(get("/api/payments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    @Test
    void 未登录过账付款单_401() throws Exception {
        mockMvc.perform(post("/api/payments/PAYV-1/post"))
                .andExpect(status().isUnauthorized());
    }
}
