package com.sjherp.app.gl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.gl.AccountBalance;
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.gl.Voucher;
import com.sjherp.domain.gl.VoucherLine;
import com.sjherp.domain.gl.VoucherNotBalancedException;
import com.sjherp.domain.gl.VoucherNotFoundException;
import com.sjherp.domain.identity.Role;

/**
 * GlVoucherController MockMvc 切片测试（M4-T01 §7）。
 *
 * <p>使用 {@code MockMvcBuilders.standaloneSetup}（不启动完整 Spring 上下文），
 * {@link GlExceptionHandler} 通过 {@code setControllerAdvice} 接入，确保异常→状态码映射正确。
 * {@code @PreAuthorize} 在 standaloneSetup 中不生效，权限测试由专项 {@code @WebMvcTest} 覆盖。
 *
 * <p>核心验收契约（拆解 §7）：
 * <ul>
 *   <li>建不平凭证 → 400 + {"error":"..."}（验收①）；</li>
 *   <li>在已关账账期过账 → 409 + {"error":"..."}（验收②）；</li>
 *   <li>凭证不存在 → 404；</li>
 *   <li>金额字段在 JSON 响应中为字符串（精度契约）。</li>
 * </ul>
 */
class GlVoucherControllerTest {

    private VoucherAppService voucherAppService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        voucherAppService = Mockito.mock(VoucherAppService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new GlVoucherController(voucherAppService))
                .setControllerAdvice(new GlExceptionHandler())
                .build();
        // standaloneSetup 不走 JWT 过滤器，直接将认证态注入 SecurityContextHolder
        // 供控制器内 CurrentUser.operator() 解析登录名（审计操作人）
        AuthenticatedUser principal = new AuthenticatedUser(1L, "alice", "爱丽丝",
                Set.of(Role.ACCOUNTANT));
        var token = new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ACCOUNTANT")));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @AfterEach
    void tearDown() {
        // 每次测试后清空 SecurityContext，避免跨用例污染
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 辅助方法

    /**
     * 构造最小合法凭证（一借一贷平衡，accountCode = 1001/6001，总额 1000.00）的 stub，
     * 并返回该凭证（从领域工厂 restore 重建，绕过重校验）。
     */
    private static Voucher balancedVoucher() {
        // 从数据库恢复的凭证行金额精度为 DECIMAL(18,2)，即 0 存储为 0.00
        List<VoucherLine> lines = List.of(
                VoucherLine.restore(1L, 1, "1001", new BigDecimal("1000.00"), new BigDecimal("0.00"), "收现"),
                VoucherLine.restore(2L, 2, "6001", new BigDecimal("0.00"), new BigDecimal("1000.00"), "收入"));
        return Voucher.restore("VCH-202606-0001", "202606", LocalDate.of(2026, 6, 1), "记",
                new BigDecimal("1000.00"), "测试摘要", null, null,
                DocumentStatus.DRAFT, lines, "alice");
    }

    /** 构造 APPROVED 状态的过账凭证 stub。 */
    private static Voucher approvedVoucher() {
        List<VoucherLine> lines = List.of(
                VoucherLine.restore(1L, 1, "1001", new BigDecimal("500.00"), new BigDecimal("0.00"), null),
                VoucherLine.restore(2L, 2, "6001", new BigDecimal("0.00"), new BigDecimal("500.00"), null));
        return Voucher.restore("VCH-202606-0002", "202606", LocalDate.of(2026, 6, 2), "记",
                new BigDecimal("500.00"), null, null, null,
                DocumentStatus.APPROVED, lines, "alice");
    }

    // ================================================================ 1. 建凭证

    /**
     * 建不平凭证（借贷 Σ 不等）→ AppService 抛 VoucherNotBalancedException
     * → GlExceptionHandler 映射 400 + {"error":"..."} （验收①）。
     */
    @Test
    void 建不平凭证_400_错误体含error字段() throws Exception {
        Mockito.when(voucherAppService.create(any(), any(), any(), anyString()))
                .thenThrow(new VoucherNotBalancedException(new BigDecimal("1000.00"),
                        new BigDecimal("800.00")));

        mockMvc.perform(post("/api/gl/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "voucherDate": "2026-06-01",
                                    "lines": [
                                        {"accountCode":"1001","debit":"1000.00"},
                                        {"accountCode":"6001","credit":"800.00"}
                                    ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());

        Mockito.verify(voucherAppService).create(any(), any(), any(), anyString());
    }

    /**
     * 建凭证成功 → 201，返回体 docNo/status/totalAmount（金额字段为字符串）。
     */
    @Test
    void 建凭证成功_201_金额字段为字符串() throws Exception {
        Mockito.when(voucherAppService.create(any(), any(), any(), anyString()))
                .thenReturn(balancedVoucher());

        mockMvc.perform(post("/api/gl/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "voucherDate": "2026-06-01",
                                    "summary": "测试摘要",
                                    "lines": [
                                        {"accountCode":"1001","debit":"1000.00"},
                                        {"accountCode":"6001","credit":"1000.00"}
                                    ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.docNo").value("VCH-202606-0001"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                // 精度契约：totalAmount 为字符串（toPlainString）
                .andExpect(jsonPath("$.totalAmount").value("1000.00"))
                .andExpect(jsonPath("$.period").value("202606"))
                // 行级金额也为字符串
                .andExpect(jsonPath("$.lines[0].debit").value("1000.00"))
                .andExpect(jsonPath("$.lines[0].credit").value("0.00"))
                .andExpect(jsonPath("$.lines[1].debit").value("0.00"))
                .andExpect(jsonPath("$.lines[1].credit").value("1000.00"));
    }

    /**
     * 建凭证请求体缺少 lines → Bean Validation 拦截 → 400（GlExceptionHandler 处理
     * MethodArgumentNotValidException）。
     */
    @Test
    void 建凭证缺lines_400_BeanValidation() throws Exception {
        mockMvc.perform(post("/api/gl/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"voucherDate": "2026-06-01"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());

        Mockito.verifyNoInteractions(voucherAppService);
    }

    /**
     * 建凭证缺必填 voucherDate → 400（Bean Validation @NotNull）。
     */
    @Test
    void 建凭证缺voucherDate_400() throws Exception {
        mockMvc.perform(post("/api/gl/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "lines": [
                                        {"accountCode":"1001","debit":"1000.00"},
                                        {"accountCode":"6001","credit":"1000.00"}
                                    ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());

        Mockito.verifyNoInteractions(voucherAppService);
    }

    // ================================================================ 2. 过账

    /**
     * 在已关账账期过账 → AppService 抛 PeriodClosedException（extends IllegalStateException）
     * → GlExceptionHandler 映射 409 + {"error":"..."} （验收②）。
     */
    @Test
    void 关账期过账_409_错误体含error字段() throws Exception {
        Mockito.when(voucherAppService.post(anyString(), anyString()))
                .thenThrow(new PeriodClosedException("202605"));

        mockMvc.perform(post("/api/gl/vouchers/VCH-202605-0001/post"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());

        Mockito.verify(voucherAppService).post(Mockito.eq("VCH-202605-0001"), anyString());
    }

    /**
     * 过账成功 → 200，返回 APPROVED 状态凭证。
     */
    @Test
    void 过账成功_200_状态为APPROVED() throws Exception {
        Mockito.when(voucherAppService.post(anyString(), anyString()))
                .thenReturn(approvedVoucher());

        mockMvc.perform(post("/api/gl/vouchers/VCH-202606-0002/post"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docNo").value("VCH-202606-0002"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.totalAmount").value("500.00"));
    }

    // ================================================================ 3. 凭证详情

    /**
     * 凭证不存在 → VoucherNotFoundException → 404（验收③）。
     */
    @Test
    void 凭证不存在_404() throws Exception {
        Mockito.when(voucherAppService.get(anyString()))
                .thenThrow(new VoucherNotFoundException("VCH-999999-0001"));

        mockMvc.perform(get("/api/gl/vouchers/VCH-999999-0001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    /**
     * 凭证详情查询正常 → 200，金额字段为字符串。
     */
    @Test
    void 凭证详情_200_金额为字符串() throws Exception {
        Mockito.when(voucherAppService.get(anyString()))
                .thenReturn(balancedVoucher());

        mockMvc.perform(get("/api/gl/vouchers/VCH-202606-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docNo").value("VCH-202606-0001"))
                // 精度契约：totalAmount 为字符串
                .andExpect(jsonPath("$.totalAmount").value("1000.00"))
                .andExpect(jsonPath("$.word").value("记"))
                // 行级金额为字符串
                .andExpect(jsonPath("$.lines[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.lines[0].debit").value("1000.00"));
    }

    // ================================================================ 4. 分页查询

    /**
     * 凭证分页查询（无参数）→ 200，返回空页（total=0）。
     */
    @Test
    void 凭证分页查询_空结果_200() throws Exception {
        Mockito.when(voucherAppService.search(any()))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 20));

        mockMvc.perform(get("/api/gl/vouchers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20));
    }

    /**
     * status 参数非法 → 400（控制器内 parseStatus 抛 IllegalArgumentException
     * → GlExceptionHandler 映射 400）。
     */
    @Test
    void 凭证分页_status非法_400() throws Exception {
        mockMvc.perform(get("/api/gl/vouchers?status=INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());

        Mockito.verifyNoInteractions(voucherAppService);
    }

    // ================================================================ 5. 试算平衡

    /**
     * 试算平衡（给定账期）→ 200，合计借贷相等且为字符串。
     */
    @Test
    void 试算平衡_200_借贷合计为字符串() throws Exception {
        List<AccountBalance> balances = List.of(
                new AccountBalance("1001", new BigDecimal("1000.00"), BigDecimal.ZERO),
                new AccountBalance("6001", BigDecimal.ZERO, new BigDecimal("1000.00")));
        Mockito.when(voucherAppService.trialBalance(anyString()))
                .thenReturn(balances);

        mockMvc.perform(get("/api/gl/trial-balance?period=202606"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("202606"))
                // Σ借 == Σ贷（试算平衡契约）且为字符串
                .andExpect(jsonPath("$.totalDebit").value("1000.00"))
                .andExpect(jsonPath("$.totalCredit").value("1000.00"))
                .andExpect(jsonPath("$.balances[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.balances[0].totalDebit").value("1000.00"))
                .andExpect(jsonPath("$.balances[0].totalCredit").value("0.00"));
    }

    // ================================================================ 6. 科目余额

    /**
     * 科目余额（accountCode + period）→ 200，金额字段为字符串。
     */
    @Test
    void 科目余额_200_金额为字符串() throws Exception {
        Mockito.when(voucherAppService.accountBalance(anyString(), anyString()))
                .thenReturn(new AccountBalance("1001", new BigDecimal("2000.00"),
                        new BigDecimal("500.00")));

        mockMvc.perform(get("/api/gl/account-balance?accountCode=1001&period=202606"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountCode").value("1001"))
                .andExpect(jsonPath("$.totalDebit").value("2000.00"))
                .andExpect(jsonPath("$.totalCredit").value("500.00"))
                // netBalance = 2000 - 500 = 1500（字符串）
                .andExpect(jsonPath("$.netBalance").value("1500.00"));
    }
}
