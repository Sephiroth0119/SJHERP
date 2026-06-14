package com.sjherp.app.gl;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sjherp.app.gl.GlDtos.ClosingPreviewLine;
import com.sjherp.app.gl.GlDtos.PeriodCloseReadiness;
import com.sjherp.app.gl.GlDtos.PeriodCloseResult;
import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.domain.gl.AccountingPeriodNotFoundException;
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.identity.Role;

/**
 * {@link GlPeriodController} 月末结转关账两个新端点的 MockMvc 切片测试（M4-T05，拆解 §2.3/§4）。
 *
 * <p>照 {@link GlVoucherControllerTest}/{@link GlPeriodControllerTest} 范式用
 * {@code standaloneSetup} + {@link GlExceptionHandler}（{@code @PreAuthorize} 不生效，权限单测由专项
 * 覆盖；本测专注序列化契约与异常→状态码映射）。覆盖：
 * <ul>
 *   <li>close → 200 返回 {@link PeriodCloseResult}（金额字符串、含结转凭证号/净利润）；</li>
 *   <li>close-precheck → 200 返回 {@link PeriodCloseReadiness}（closeable/ERROR-WARN/结转预览）；</li>
 *   <li>{@link PeriodCloseBlockedException} → 409，体含 {@code reasons} 数组（区别于普通 {error}）；</li>
 *   <li>{@link PeriodClosedException} → 409；账期不存在 → 404。</li>
 * </ul>
 */
class GlPeriodCloseControllerTest {

    private AccountingPeriodAppService accountingPeriodAppService;
    private PeriodCloseService periodCloseService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        accountingPeriodAppService = Mockito.mock(AccountingPeriodAppService.class);
        periodCloseService = Mockito.mock(PeriodCloseService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new GlPeriodController(accountingPeriodAppService, periodCloseService))
                .setControllerAdvice(new GlExceptionHandler())
                .build();
        AuthenticatedUser principal = new AuthenticatedUser(1L, "alice", "爱丽丝",
                Set.of(Role.ACCOUNTANT));
        var token = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ACCOUNTANT")));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 辅助

    private static PeriodCloseResult sampleResult() {
        return new PeriodCloseResult("202606", "VCH-202606-0001", "1000.00", "600.00", "400.00",
                "5000.00", "5000.00", "alice", "2026-06-30T00:00:00Z");
    }

    private static PeriodCloseReadiness sampleReadiness(boolean closeable) {
        return new PeriodCloseReadiness("202606", "OPEN", closeable, false,
                List.of(), List.of("[SALES_THREE_WAY] SO-1 越界"),
                List.of(new ClosingPreviewLine("6001", "主营业务收入", "1000.00", "0.00"),
                        new ClosingPreviewLine("4103", "本年利润", "0.00", "1000.00")),
                "1000.00", "600.00", "400.00", "5000.00", "5000.00");
    }

    // ================================================================ 1. close

    @Test
    void 关账成功_200_返回结转凭证号与净利润_金额为字符串() throws Exception {
        Mockito.when(periodCloseService.close(anyString(), anyString())).thenReturn(sampleResult());

        mockMvc.perform(post("/api/gl/periods/202606/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("202606"))
                .andExpect(jsonPath("$.closingVoucherDocNo").value("VCH-202606-0001"))
                .andExpect(jsonPath("$.totalRevenue").value("1000.00"))
                .andExpect(jsonPath("$.totalExpense").value("600.00"))
                .andExpect(jsonPath("$.netProfit").value("400.00"))
                .andExpect(jsonPath("$.trialBalanceDebit").value("5000.00"))
                .andExpect(jsonPath("$.trialBalanceCredit").value("5000.00"))
                .andExpect(jsonPath("$.closedBy").value("alice"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());

        // operator 透传（CurrentUser.operator() = alice）
        Mockito.verify(periodCloseService).close(eq("202606"), anyString());
    }

    @Test
    void 关账无损益发生额_200_结转凭证号为null() throws Exception {
        PeriodCloseResult noVoucher = new PeriodCloseResult("202606", null, "0.00", "0.00", "0.00",
                "0.00", "0.00", "alice", "2026-06-30T00:00:00Z");
        Mockito.when(periodCloseService.close(anyString(), anyString())).thenReturn(noVoucher);

        mockMvc.perform(post("/api/gl/periods/202606/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closingVoucherDocNo").isEmpty())
                .andExpect(jsonPath("$.netProfit").value("0.00"));
    }

    @Test
    void 关账被闸门拒_409_体含reasons数组() throws Exception {
        Mockito.when(periodCloseService.close(anyString(), anyString()))
                .thenThrow(new PeriodCloseBlockedException("账期[202606] 存在 2 项数据一致性错误",
                        List.of("[LEDGER_QUANTITY] warehouse=1,product=2 库存账实不平（期望=100, 实际=99）",
                                "[PAYABLE_AMOUNT] PI-202606-0001 应付金额勾稽不平（期望=500, 实际=480）")));

        mockMvc.perform(post("/api/gl/periods/202606/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty())
                // 区别于普通 {error}：体含 reasons 数组供向导/Agent 复述
                .andExpect(jsonPath("$.reasons").isArray())
                .andExpect(jsonPath("$.reasons.length()").value(2))
                .andExpect(jsonPath("$.reasons[0]").value(
                        org.hamcrest.Matchers.containsString("LEDGER_QUANTITY")));
    }

    @Test
    void 关账账期已CLOSED被拒_409_单原因reasons() throws Exception {
        // 单原因构造器：reasons = List.of(message)
        Mockito.when(periodCloseService.close(anyString(), anyString()))
                .thenThrow(new PeriodCloseBlockedException(
                        "账期[202606] 当前状态为 关闭，仅 OPEN 账期可关账"));

        mockMvc.perform(post("/api/gl/periods/202606/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasons").isArray())
                .andExpect(jsonPath("$.reasons.length()").value(1));
    }

    @Test
    void 关账试算断言失败_IllegalState_409() throws Exception {
        // ⑤ 试算断言兜底抛 IllegalStateException（非 Blocked）→ 走通用 409
        Mockito.when(periodCloseService.close(anyString(), anyString()))
                .thenThrow(new IllegalStateException("账期[202606] 结转后试算不平：Σ借 ≠ Σ贷"));

        mockMvc.perform(post("/api/gl/periods/202606/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void 关账期已关闭过账被拒_PeriodClosed_409() throws Exception {
        // 结转凭证 post 若命中已关账期（理论不会，OPEN 已校验）→ PeriodClosedException → 专属 409
        Mockito.when(periodCloseService.close(anyString(), anyString()))
                .thenThrow(new PeriodClosedException("202606"));

        mockMvc.perform(post("/api/gl/periods/202606/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void 关账账期不存在_404() throws Exception {
        Mockito.when(periodCloseService.close(anyString(), anyString()))
                .thenThrow(new AccountingPeriodNotFoundException("999999"));

        mockMvc.perform(post("/api/gl/periods/999999/close"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ================================================================ 2. close-precheck

    @Test
    void 关账预检_200_返回readiness_含结转预览与净利润() throws Exception {
        Mockito.when(periodCloseService.precheck(anyString())).thenReturn(sampleReadiness(true));

        mockMvc.perform(get("/api/gl/periods/202606/close-precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("202606"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.closeable").value(true))
                .andExpect(jsonPath("$.alreadyClosed").value(false))
                .andExpect(jsonPath("$.consistencyErrors").isArray())
                .andExpect(jsonPath("$.consistencyErrors.length()").value(0))
                .andExpect(jsonPath("$.consistencyWarnings.length()").value(1))
                .andExpect(jsonPath("$.closingPreviewLines[0].accountCode").value("6001"))
                .andExpect(jsonPath("$.closingPreviewLines[0].debit").value("1000.00"))
                .andExpect(jsonPath("$.closingPreviewLines[1].accountCode").value("4103"))
                .andExpect(jsonPath("$.netProfit").value("400.00"))
                .andExpect(jsonPath("$.trialBalanceDebit").value("5000.00"));

        Mockito.verify(periodCloseService).precheck("202606");
    }

    @Test
    void 关账预检_不可关账_closeable为false() throws Exception {
        Mockito.when(periodCloseService.precheck(anyString())).thenReturn(sampleReadiness(false));

        mockMvc.perform(get("/api/gl/periods/202606/close-precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closeable").value(false));
    }

    @Test
    void 关账预检账期不存在_404() throws Exception {
        Mockito.when(periodCloseService.precheck(anyString()))
                .thenThrow(new AccountingPeriodNotFoundException("999999"));

        mockMvc.perform(get("/api/gl/periods/999999/close-precheck"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }
}
