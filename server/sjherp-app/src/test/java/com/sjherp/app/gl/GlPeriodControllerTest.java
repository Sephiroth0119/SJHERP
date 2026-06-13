package com.sjherp.app.gl;

import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import com.sjherp.domain.gl.AccountingPeriod;
import com.sjherp.domain.gl.AccountingPeriodNotFoundException;
import com.sjherp.domain.gl.PeriodStatus;
import com.sjherp.domain.identity.Role;

/**
 * GlPeriodController MockMvc 切片测试（M4-T01 §7）。
 *
 * <p>使用 {@code MockMvcBuilders.standaloneSetup}，{@link GlExceptionHandler} 通过
 * {@code setControllerAdvice} 接入。{@code @PreAuthorize} 在 standaloneSetup 中不生效，
 * 权限测试由专项 {@code @WebMvcTest} 覆盖。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>开启账期 → 201，账期键/年月/状态断言；</li>
 *   <li>关账 → 200，状态变为 CLOSED；</li>
 *   <li>重复关账 → 409（IllegalStateException → GlExceptionHandler）；</li>
 *   <li>重开账期 → 200，状态回 OPEN；</li>
 *   <li>账期不存在 → 404；</li>
 *   <li>账期列表 → 200，返回数组；</li>
 *   <li>账期详情 → 200，字段断言（closedBy/closedAt 为 null 时字段值为空）。</li>
 * </ul>
 */
class GlPeriodControllerTest {

    private AccountingPeriodAppService accountingPeriodAppService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        accountingPeriodAppService = Mockito.mock(AccountingPeriodAppService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new GlPeriodController(accountingPeriodAppService))
                .setControllerAdvice(new GlExceptionHandler())
                .build();
        // standaloneSetup 不走 JWT 过滤器，直接将认证态注入 SecurityContextHolder
        // 供控制器内 CurrentUser.operator() 解析登录名
        AuthenticatedUser principal = new AuthenticatedUser(1L, "alice", "爱丽丝",
                Set.of(Role.ACCOUNTANT));
        var token = new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ACCOUNTANT")));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 辅助方法

    /**
     * 从工厂重建 OPEN 状态账期（restore 不重跑业务校验）。
     */
    private static AccountingPeriod openPeriod(String period) {
        int year = Integer.parseInt(period.substring(0, 4));
        int month = Integer.parseInt(period.substring(4, 6));
        Instant now = Instant.now();
        return AccountingPeriod.restore(1L, period, year, month,
                PeriodStatus.OPEN, null, null,
                "alice", now, "alice", now);
    }

    /**
     * 从工厂重建 CLOSED 状态账期（已关账，含 closedBy/closedAt）。
     */
    private static AccountingPeriod closedPeriod(String period) {
        int year = Integer.parseInt(period.substring(0, 4));
        int month = Integer.parseInt(period.substring(4, 6));
        Instant now = Instant.now();
        return AccountingPeriod.restore(1L, period, year, month,
                PeriodStatus.CLOSED, "alice", now,
                "alice", now, "alice", now);
    }

    // ================================================================ 1. 开启账期

    /**
     * 开启账期 → 201 Created，返回账期键 / 年月 / OPEN 状态。
     */
    @Test
    void 开启账期_201_返回期间键和OPEN状态() throws Exception {
        Mockito.when(accountingPeriodAppService.open(anyString(), anyString()))
                .thenReturn(openPeriod("202606"));

        mockMvc.perform(post("/api/gl/periods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"period":"202606"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.period").value("202606"))
                .andExpect(jsonPath("$.year").value(2026))
                .andExpect(jsonPath("$.month").value(6))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.statusLabel").value("开启"))
                // OPEN 状态无关账人/时间（JSON 值为 null，对应 jsonPath isEmpty）
                .andExpect(jsonPath("$.closedBy").isEmpty())
                .andExpect(jsonPath("$.closedAt").isEmpty());
    }

    /**
     * 开启账期请求体缺 period 字段 → Bean Validation @NotNull → 400。
     */
    @Test
    void 开启账期缺period_400_BeanValidation() throws Exception {
        mockMvc.perform(post("/api/gl/periods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());

        Mockito.verifyNoInteractions(accountingPeriodAppService);
    }

    /**
     * 开启重复账期 → AppService/领域层抛 IllegalArgumentException（已存在拒绝）→ 400。
     */
    @Test
    void 开启重复账期_400() throws Exception {
        Mockito.when(accountingPeriodAppService.open(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("账期[202606] 已存在，不可重复开启"));

        mockMvc.perform(post("/api/gl/periods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"period":"202606"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ================================================================ 2. 关账

    /**
     * 关账（OPEN → CLOSED）→ 200，状态变为 CLOSED，closedBy 有值。
     */
    @Test
    void 关账_200_状态CLOSED_含关账人() throws Exception {
        Mockito.when(accountingPeriodAppService.close(anyString(), anyString()))
                .thenReturn(closedPeriod("202606"));

        mockMvc.perform(post("/api/gl/periods/202606/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("202606"))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.statusLabel").value("关闭"))
                .andExpect(jsonPath("$.closedBy").value("alice"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
    }

    /**
     * 重复关账 → 领域层 AccountingPeriod.close 抛 IllegalStateException
     * → GlExceptionHandler 映射 409。
     */
    @Test
    void 重复关账_409_已关账不可再关() throws Exception {
        Mockito.when(accountingPeriodAppService.close(anyString(), anyString()))
                .thenThrow(new IllegalStateException("账期[202606] 已关闭，不可重复关账"));

        mockMvc.perform(post("/api/gl/periods/202606/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ================================================================ 3. 重开账期

    /**
     * 重开账期（CLOSED → OPEN，高敏操作）→ 200，状态变回 OPEN，closedBy 清空。
     */
    @Test
    void 重开账期_200_状态回OPEN_关账字段清空() throws Exception {
        Mockito.when(accountingPeriodAppService.reopen(anyString(), anyString()))
                .thenReturn(openPeriod("202606"));

        mockMvc.perform(post("/api/gl/periods/202606/reopen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                // 重开后关账人/时间清空
                .andExpect(jsonPath("$.closedBy").isEmpty())
                .andExpect(jsonPath("$.closedAt").isEmpty());
    }

    /**
     * 对已开启账期重开 → 领域层抛 IllegalStateException → 409。
     */
    @Test
    void 重开已开启账期_409() throws Exception {
        Mockito.when(accountingPeriodAppService.reopen(anyString(), anyString()))
                .thenThrow(new IllegalStateException("账期[202606] 当前已开启，无需重开"));

        mockMvc.perform(post("/api/gl/periods/202606/reopen"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ================================================================ 4. 账期不存在

    /**
     * 账期不存在 → AccountingPeriodNotFoundException → 404。
     */
    @Test
    void 账期不存在_404() throws Exception {
        Mockito.when(accountingPeriodAppService.get(anyString()))
                .thenThrow(new AccountingPeriodNotFoundException("999999"));

        mockMvc.perform(get("/api/gl/periods/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ================================================================ 5. 账期列表

    /**
     * 账期列表 → 200，返回 JSON 数组，断言账期键与状态。
     */
    @Test
    void 账期列表_200_返回数组() throws Exception {
        Instant now = Instant.now();
        List<AccountingPeriod> periods = List.of(
                AccountingPeriod.restore(1L, "202605", 2026, 5, PeriodStatus.CLOSED,
                        "alice", now, "alice", now, "alice", now),
                AccountingPeriod.restore(2L, "202606", 2026, 6, PeriodStatus.OPEN,
                        null, null, "alice", now, "alice", now));
        Mockito.when(accountingPeriodAppService.listAll()).thenReturn(periods);

        mockMvc.perform(get("/api/gl/periods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].period").value("202605"))
                .andExpect(jsonPath("$[0].status").value("CLOSED"))
                .andExpect(jsonPath("$[1].period").value("202606"))
                .andExpect(jsonPath("$[1].status").value("OPEN"));
    }

    // ================================================================ 6. 账期详情

    /**
     * 账期详情（OPEN 状态）→ 200，字段断言（closedBy/closedAt 为空）。
     */
    @Test
    void 账期详情_OPEN状态_200_关账字段为空() throws Exception {
        Mockito.when(accountingPeriodAppService.get(anyString()))
                .thenReturn(openPeriod("202606"));

        mockMvc.perform(get("/api/gl/periods/202606"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("202606"))
                .andExpect(jsonPath("$.year").value(2026))
                .andExpect(jsonPath("$.month").value(6))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.closedBy").isEmpty())
                .andExpect(jsonPath("$.closedAt").isEmpty());
    }

    /**
     * 账期详情（CLOSED 状态）→ 200，closedBy 与 closedAt 有值。
     */
    @Test
    void 账期详情_CLOSED状态_200_含关账人和时间() throws Exception {
        Mockito.when(accountingPeriodAppService.get(anyString()))
                .thenReturn(closedPeriod("202605"));

        mockMvc.perform(get("/api/gl/periods/202605"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("202605"))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedBy").value("alice"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
    }
}
