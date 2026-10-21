package com.sjherp.app.ops;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.sjherp.app.ops.HttpMetricsSummaryService.HttpMetricsSummary;
import com.sjherp.app.ops.LlmDailySummaryService.LlmDailySummary;
import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.app.security.SecurityConfig;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;

@WebMvcTest(controllers = OpsController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, OpsExceptionHandler.class})
class OpsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmDailySummaryService llmDailySummaryService;

    @MockitoBean
    private HttpMetricsSummaryService httpMetricsSummaryService;

    @MockitoBean
    private UserRepository userRepository;

    private static RequestPostProcessor asUser(Role... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "tester", "测试用户", Set.of(roles));
        var authorities = Set.of(roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    @Test
    void 未登录_401() throws Exception {
        mockMvc.perform(get("/api/ops/llm-daily"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
        verifyNoInteractions(llmDailySummaryService);
    }

    @Test
    void 仓管_403() throws Exception {
        mockMvc.perform(get("/api/ops/llm-daily").with(asUser(Role.WAREHOUSE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        verifyNoInteractions(llmDailySummaryService);
    }

    @Test
    void 老板可查日报() throws Exception {
        when(llmDailySummaryService.summarize("2026-10-20")).thenReturn(new LlmDailySummary(
                "2026-10-20", "2026-10-20T00:00:00Z", "2026-10-21T00:00:00Z",
                2, 0, 10, 4, 30, null, null, "未配置单价", List.of()));
        mockMvc.perform(get("/api/ops/llm-daily").param("date", "2026-10-20").with(asUser(Role.BOSS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-10-20"))
                .andExpect(jsonPath("$.llmCalls").value(2))
                .andExpect(jsonPath("$.estimatedCost").value(nullValue()));
        verify(llmDailySummaryService).summarize("2026-10-20");
    }

    @Test
    void 管理员可查HTTP汇总() throws Exception {
        when(httpMetricsSummaryService.snapshot()).thenReturn(
                new HttpMetricsSummary(9, 1, 0, "12.00", "40.00", "note"));
        mockMvc.perform(get("/api/ops/http-summary").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests").value(9))
                .andExpect(jsonPath("$.errors4xx").value(1))
                .andExpect(jsonPath("$.maxLatencyMs").value("12.00"));
    }

    @Test
    void 非法日期_400() throws Exception {
        when(llmDailySummaryService.summarize("nope"))
                .thenThrow(new IllegalArgumentException("date 必须是 UTC 的 yyyy-MM-dd"));
        mockMvc.perform(get("/api/ops/llm-daily").param("date", "nope").with(asUser(Role.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("date 必须是 UTC 的 yyyy-MM-dd"));
    }
}
