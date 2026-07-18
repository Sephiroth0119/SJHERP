package com.sjherp.app.consistency;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.consistency.ConsistencyCheckRun;
import com.sjherp.domain.consistency.ConsistencyFinding;
import com.sjherp.domain.identity.Role;

class ConsistencyControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-19T01:02:03Z");

    private final ConsistencyCheckService checkService = mock(ConsistencyCheckService.class);
    private final ConsistencyCheckRunner runner = mock(ConsistencyCheckRunner.class);
    private final ConsistencyReportService reportService = mock(ConsistencyReportService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new ConsistencyController(checkService, runner, reportService)).build();
        AuthenticatedUser principal = new AuthenticatedUser(
                7L, "admin", "管理员", Set.of(Role.ADMIN));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getCheckRemainsPureAndDoesNotUseRunnerOrHistory() throws Exception {
        when(checkService.check()).thenReturn(new ConsistencyReport(NOW, List.of()));

        mockMvc.perform(get("/api/consistency/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedAt").exists())
                .andExpect(jsonPath("$.clean").value(true));

        verify(checkService).check();
        verify(runner, never()).runManual(org.mockito.ArgumentMatchers.anyString());
        verify(reportService, never()).search(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void postRunUsesCurrentOperatorAndReturnsPersistedSummary() throws Exception {
        ConsistencyCheckRun run = run(List.of());
        when(runner.runManual("admin")).thenReturn(run);

        mockMvc.perform(post("/api/consistency/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runNo").value(run.runNo()))
                .andExpect(jsonPath("$.triggerType").value("MANUAL_API"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.clean").value(true))
                .andExpect(jsonPath("$.totalCount").value(0));

        verify(runner).runManual("admin");
    }

    @Test
    void reportListUsesValidatedPagingAndReturnsSummariesOnly() throws Exception {
        ConsistencyCheckRun run = run(List.of(finding()));
        when(reportService.search(2, 10))
                .thenReturn(new PageResult<>(List.of(run), 1, 2, 10));

        mockMvc.perform(get("/api/consistency/reports").param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.items[0].runNo").value(run.runNo()))
                .andExpect(jsonPath("$.items[0].totalCount").value(1))
                .andExpect(jsonPath("$.items[0].findings").doesNotExist());

        verify(reportService).search(2, 10);
    }

    @Test
    void reportDetailMapsBigDecimalsAsPlainStrings() throws Exception {
        ConsistencyCheckRun run = run(List.of(finding()));
        when(reportService.get(run.runNo())).thenReturn(run);

        mockMvc.perform(get("/api/consistency/reports/{runNo}", run.runNo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runNo").value(run.runNo()))
                .andExpect(jsonPath("$.findings[0].ruleCode").value("CORE_SQL_ASSERTIONS"))
                .andExpect(jsonPath("$.findings[0].expected").value("10.123456"))
                .andExpect(jsonPath("$.findings[0].actual").value("9.000000"))
                .andExpect(jsonPath("$.findings[0].message").value("库存金额恒等式破坏"));
    }

    @Test
    void missingReportIs404AndInvalidPagingIs400() throws Exception {
        when(reportService.get("CHK-missing"))
                .thenThrow(new ConsistencyReportNotFoundException("CHK-missing"));
        when(reportService.search(0, 20)).thenThrow(new IllegalArgumentException("分页参数不合法"));

        mockMvc.perform(get("/api/consistency/reports/CHK-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("一致性检查报告不存在: CHK-missing"));
        mockMvc.perform(get("/api/consistency/reports").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("分页参数不合法"));
    }

    private static ConsistencyCheckRun run(List<ConsistencyFinding> findings) {
        return ConsistencyCheckRun.completed(0, "CHK-202607-0001",
                ConsistencyCheckRun.TriggerType.MANUAL_API, "admin", NOW, NOW,
                ConsistencyCheckRun.AnalysisStatus.SKIPPED, null, findings);
    }

    private static ConsistencyFinding finding() {
        return new ConsistencyFinding(1, "CORE_SQL_ASSERTIONS", "LEDGER_COST",
                "warehouse=1,product=2", new BigDecimal("10.123456"),
                new BigDecimal("9.000000"), ConsistencyFinding.Severity.ERROR,
                "库存金额恒等式破坏");
    }
}
