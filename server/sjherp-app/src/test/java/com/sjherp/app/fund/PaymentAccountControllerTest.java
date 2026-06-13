package com.sjherp.app.fund;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.fund.PaymentAccount;
import com.sjherp.domain.fund.PaymentAccountCommand;
import com.sjherp.domain.fund.PaymentAccountNotFoundException;
import com.sjherp.domain.fund.PaymentAccountQuery;
import com.sjherp.domain.fund.PaymentAccountService;
import com.sjherp.domain.fund.PaymentAccountType;
import com.sjherp.domain.identity.Role;

/**
 * PaymentAccountController MockMvc 切片测试（M4-T04a，照 {@code GlAccountControllerTest} 范式）。
 *
 * <p>使用 {@code MockMvcBuilders.standaloneSetup}，{@link PaymentAccountExceptionHandler} 通过
 * {@code setControllerAdvice} 接入；{@code @PreAuthorize} 在 standaloneSetup 中不生效，
 * 鉴权放行/403/401 由专项 {@code PaymentAccountApiPermissionTest}（@WebMvcTest 真 SecurityConfig）覆盖。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>建档 → 201，命令映射 + 返回字段（accountType / accountTypeLabel / glAccountCode / status）；</li>
 *   <li>建档缺必填（name / accountType / glAccountCode）→ 400（Bean Validation）；</li>
 *   <li>accountType 非法 → 400（DTO parseType 抛 IllegalArgumentException）；</li>
 *   <li>glAccountCode 非法（不存在/停用/非末级）→ 400（领域服务拒绝）；</li>
 *   <li>编码重复 → 400；更新 → 200；启用/停用 → 200；重复启停 → 400；</li>
 *   <li>按 id 查不存在 → 404；列表 → 200（分页结构）；status 过滤参数非法 → 400。</li>
 * </ul>
 */
class PaymentAccountControllerTest {

    private PaymentAccountService paymentAccountService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        paymentAccountService = Mockito.mock(PaymentAccountService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PaymentAccountController(paymentAccountService))
                .setControllerAdvice(new PaymentAccountExceptionHandler())
                .build();
        // standaloneSetup 不走 JWT 过滤器，直接注入认证态供控制器内 CurrentUser.operator() 解析登录名
        AuthenticatedUser principal = new AuthenticatedUser(1L, "alice", "爱丽丝", Set.of(Role.ACCOUNTANT));
        var token = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_ACCOUNTANT")));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 辅助

    /** 从 restore 工厂重建资金账户（不重跑业务校验），方便构造 stub 返回值 */
    private static PaymentAccount account(long id, String code, String name, PaymentAccountType type,
                                          String glAccountCode, String bankName, String accountNo,
                                          ArchiveStatus status) {
        Instant now = Instant.now();
        return PaymentAccount.restore(id, code, name, type, glAccountCode, bankName, accountNo, status,
                "alice", now, "alice", now);
    }

    // ================================================================ 1. 建档

    @Test
    void 建档_201_命令映射与字段序列化() throws Exception {
        PaymentAccount saved = account(5L, "FA-202606-0001", "工行基本户", PaymentAccountType.BANK,
                "1002", "工商银行", "6222001", ArchiveStatus.ENABLED);
        Mockito.when(paymentAccountService.create(any(PaymentAccountCommand.class), anyString()))
                .thenReturn(saved);

        mockMvc.perform(post("/api/fund/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "工行基本户",
                                    "accountType": "BANK",
                                    "glAccountCode": "1002",
                                    "bankName": "工商银行",
                                    "accountNo": "6222001"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.code").value("FA-202606-0001"))
                .andExpect(jsonPath("$.name").value("工行基本户"))
                .andExpect(jsonPath("$.accountType").value("BANK"))
                .andExpect(jsonPath("$.accountTypeLabel").value("银行存款"))
                .andExpect(jsonPath("$.glAccountCode").value("1002"))
                .andExpect(jsonPath("$.bankName").value("工商银行"))
                .andExpect(jsonPath("$.accountNo").value("6222001"))
                .andExpect(jsonPath("$.status").value("ENABLED"))
                .andExpect(jsonPath("$.createdBy").value("alice"));

        // 命令映射：code 留空（自动编号）、accountType 解析为枚举、glAccountCode 透传
        ArgumentCaptor<PaymentAccountCommand> captor = ArgumentCaptor.forClass(PaymentAccountCommand.class);
        Mockito.verify(paymentAccountService).create(captor.capture(), eq("alice"));
        PaymentAccountCommand command = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(command.code()).isNull();
        org.assertj.core.api.Assertions.assertThat(command.name()).isEqualTo("工行基本户");
        org.assertj.core.api.Assertions.assertThat(command.accountType()).isEqualTo(PaymentAccountType.BANK);
        org.assertj.core.api.Assertions.assertThat(command.glAccountCode()).isEqualTo("1002");
    }

    @Test
    void 建档缺name_400_BeanValidation() throws Exception {
        mockMvc.perform(post("/api/fund/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "accountType": "BANK",
                                    "glAccountCode": "1002"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(paymentAccountService);
    }

    @Test
    void 建档缺glAccountCode_400_BeanValidation() throws Exception {
        mockMvc.perform(post("/api/fund/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "工行基本户",
                                    "accountType": "BANK"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(paymentAccountService);
    }

    @Test
    void 建档类别非法_400_DTO解析拒绝() throws Exception {
        mockMvc.perform(post("/api/fund/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "工行基本户",
                                    "accountType": "INVALID",
                                    "glAccountCode": "1002"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        // accountType 解析在 DTO.toCommand() 之前抛 IllegalArgumentException，未触达 service
        Mockito.verifyNoInteractions(paymentAccountService);
    }

    @Test
    void 建档glAccountCode非法_400_领域服务拒绝() throws Exception {
        Mockito.when(paymentAccountService.create(any(PaymentAccountCommand.class), anyString()))
                .thenThrow(new IllegalArgumentException("GL 科目不是末级科目，不能用于资金账户挂账: 1001"));

        mockMvc.perform(post("/api/fund/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "现金",
                                    "accountType": "CASH",
                                    "glAccountCode": "1001"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("末级")));
    }

    @Test
    void 建档编码重复_400() throws Exception {
        Mockito.when(paymentAccountService.create(any(PaymentAccountCommand.class), anyString()))
                .thenThrow(new IllegalArgumentException("资金账户编码已存在: FA-1"));

        mockMvc.perform(post("/api/fund/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "FA-1",
                                    "name": "现金",
                                    "accountType": "CASH",
                                    "glAccountCode": "1001"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ================================================================ 2. 更新

    @Test
    void 更新_200_命令带id() throws Exception {
        PaymentAccount updated = account(7L, "FA-202606-0007", "招行户", PaymentAccountType.BANK,
                "1002", "招商银行", "999", ArchiveStatus.ENABLED);
        Mockito.when(paymentAccountService.update(eq(7L), any(PaymentAccountCommand.class), anyString()))
                .thenReturn(updated);

        mockMvc.perform(put("/api/fund/accounts/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "FA-202606-0007",
                                    "name": "招行户",
                                    "accountType": "BANK",
                                    "glAccountCode": "1002",
                                    "bankName": "招商银行",
                                    "accountNo": "999"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("招行户"))
                .andExpect(jsonPath("$.accountTypeLabel").value("银行存款"));

        Mockito.verify(paymentAccountService).update(eq(7L), any(PaymentAccountCommand.class), eq("alice"));
    }

    @Test
    void 更新不存在_404() throws Exception {
        Mockito.when(paymentAccountService.update(anyLong(), any(PaymentAccountCommand.class), anyString()))
                .thenThrow(PaymentAccountNotFoundException.account(99L));

        mockMvc.perform(put("/api/fund/accounts/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "FA-99",
                                    "name": "x",
                                    "accountType": "CASH",
                                    "glAccountCode": "1001"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ================================================================ 3. 启用 / 停用

    @Test
    void 停用_200() throws Exception {
        PaymentAccount disabled = account(5L, "FA-1", "现金", PaymentAccountType.CASH, "1001",
                null, null, ArchiveStatus.DISABLED);
        Mockito.when(paymentAccountService.disable(eq(5L), anyString())).thenReturn(disabled);

        mockMvc.perform(post("/api/fund/accounts/5/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    @Test
    void 启用_200() throws Exception {
        PaymentAccount enabled = account(5L, "FA-1", "现金", PaymentAccountType.CASH, "1001",
                null, null, ArchiveStatus.ENABLED);
        Mockito.when(paymentAccountService.enable(eq(5L), anyString())).thenReturn(enabled);

        mockMvc.perform(post("/api/fund/accounts/5/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENABLED"));
    }

    @Test
    void 重复停用_400() throws Exception {
        Mockito.when(paymentAccountService.disable(anyLong(), anyString()))
                .thenThrow(new IllegalArgumentException("资金账户[FA-1] 已是停用状态，无需重复停用"));

        mockMvc.perform(post("/api/fund/accounts/5/disable"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ================================================================ 4. 查询

    @Test
    void 按id查不存在_404() throws Exception {
        Mockito.when(paymentAccountService.get(99L)).thenThrow(PaymentAccountNotFoundException.account(99L));

        mockMvc.perform(get("/api/fund/accounts/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void 按id查存在_200_字段完整() throws Exception {
        PaymentAccount one = account(5L, "FA-1", "其他货币资金户", PaymentAccountType.OTHER, "1012",
                null, null, ArchiveStatus.ENABLED);
        Mockito.when(paymentAccountService.get(5L)).thenReturn(one);

        mockMvc.perform(get("/api/fund/accounts/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("FA-1"))
                .andExpect(jsonPath("$.accountType").value("OTHER"))
                .andExpect(jsonPath("$.accountTypeLabel").value("其他货币资金"))
                .andExpect(jsonPath("$.glAccountCode").value("1012"));
    }

    @Test
    void 列表_200_分页结构() throws Exception {
        PaymentAccount a = account(1L, "FA-1", "现金", PaymentAccountType.CASH, "1001", null, null,
                ArchiveStatus.ENABLED);
        PaymentAccount b = account(2L, "FA-2", "工行户", PaymentAccountType.BANK, "1002", "工行", "62",
                ArchiveStatus.ENABLED);
        Mockito.when(paymentAccountService.search(any(PaymentAccountQuery.class)))
                .thenReturn(new PageResult<>(List.of(a, b), 2L, 1, 20));

        mockMvc.perform(get("/api/fund/accounts?keyword=户&page=1&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].code").value("FA-1"))
                .andExpect(jsonPath("$.items[1].accountTypeLabel").value("银行存款"));

        // keyword 透传到 Query
        ArgumentCaptor<PaymentAccountQuery> captor = ArgumentCaptor.forClass(PaymentAccountQuery.class);
        Mockito.verify(paymentAccountService).search(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().keyword()).isEqualTo("户");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().status()).isNull();
    }

    @Test
    void 列表status过滤生效() throws Exception {
        Mockito.when(paymentAccountService.search(any(PaymentAccountQuery.class)))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 20));

        mockMvc.perform(get("/api/fund/accounts?status=DISABLED"))
                .andExpect(status().isOk());

        ArgumentCaptor<PaymentAccountQuery> captor = ArgumentCaptor.forClass(PaymentAccountQuery.class);
        Mockito.verify(paymentAccountService).search(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().status()).isEqualTo(ArchiveStatus.DISABLED);
    }

    @Test
    void 列表status参数非法_400() throws Exception {
        mockMvc.perform(get("/api/fund/accounts?status=FOO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(paymentAccountService);
    }
}
