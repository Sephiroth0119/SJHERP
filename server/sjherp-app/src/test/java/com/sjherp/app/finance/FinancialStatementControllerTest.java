package com.sjherp.app.finance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sjherp.app.finance.FinancialStatementDtos.BalanceSheet;
import com.sjherp.app.finance.FinancialStatementDtos.BalanceSheetLine;
import com.sjherp.app.finance.FinancialStatementDtos.IncomeStatement;
import com.sjherp.app.finance.FinancialStatementDtos.IncomeStatementLine;

/**
 * {@link FinancialStatementController} MockMvc 切片测试（M4-T06）。
 *
 * <p>{@code standaloneSetup}（不启动 Spring 上下文、不触发 {@code @PreAuthorize}）：只验证参数绑定、
 * period 必填/格式校验、金额字符串与 balanced 字段 JSON 序列化、净利润两列暴露、非法参数 → 400
 * {"error":...}（控制器 {@code @ExceptionHandler}）。权限 401/403 由
 * {@link FinancialStatementApiPermissionTest}（@WebMvcTest + 真实 SecurityConfig）覆盖。
 */
class FinancialStatementControllerTest {

    private FinancialStatementService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(FinancialStatementService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new FinancialStatementController(service))
                .build();
    }

    // ================================================================ 夹具

    /** 一张平衡的资产负债表：资产 1000 = 负债 0 + 权益 1000。 */
    private static BalanceSheet balancedSheet() {
        return new BalanceSheet("202606",
                List.of(new BalanceSheetLine("货币资金", "1000.00")), "1000.00",
                List.of(), "0",
                List.of(new BalanceSheetLine("实收资本", "1000.00")), "1000.00",
                true);
    }

    /** 一张利润表：营业收入 1000、净利润 400（本期）/ 2400（本年累计）。 */
    private static IncomeStatement incomeStmt() {
        return new IncomeStatement("202606",
                List.of(
                        new IncomeStatementLine("一、营业收入", "1000.00", "6000.00"),
                        new IncomeStatementLine("四、净利润", "400.00", "2400.00")),
                "400.00", "2400.00");
    }

    // ================================================================ 1. 资产负债表

    @Test
    void 资产负债表_正常请求_200_金额字符串与balanced字段() throws Exception {
        Mockito.when(service.balanceSheet("202606")).thenReturn(balancedSheet());

        mockMvc.perform(get("/api/reports/balance-sheet?period=202606"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("202606"))
                .andExpect(jsonPath("$.assetLines[0].name").value("货币资金"))
                .andExpect(jsonPath("$.assetLines[0].amount").value("1000.00"))
                .andExpect(jsonPath("$.totalAssets").value("1000.00"))
                .andExpect(jsonPath("$.totalLiabilities").value("0"))
                .andExpect(jsonPath("$.equityLines[0].amount").value("1000.00"))
                .andExpect(jsonPath("$.totalEquity").value("1000.00"))
                .andExpect(jsonPath("$.balanced").value(true));
    }

    @Test
    void 资产负债表_缺period_400_错误体非空() throws Exception {
        mockMvc.perform(get("/api/reports/balance-sheet"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(service);
    }

    @Test
    void 资产负债表_period格式非法_400() throws Exception {
        mockMvc.perform(get("/api/reports/balance-sheet?period=2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(service);
    }

    @Test
    void 资产负债表_period含非数字_400() throws Exception {
        mockMvc.perform(get("/api/reports/balance-sheet?period=2026AB"))
                .andExpect(status().isBadRequest());
        Mockito.verifyNoInteractions(service);
    }

    // ================================================================ 2. 利润表

    @Test
    void 利润表_正常请求_200_两列字符串与净利润冗余字段() throws Exception {
        Mockito.when(service.incomeStatement("202606")).thenReturn(incomeStmt());

        mockMvc.perform(get("/api/reports/income-statement?period=202606"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("202606"))
                .andExpect(jsonPath("$.lines[0].name").value("一、营业收入"))
                .andExpect(jsonPath("$.lines[0].currentPeriod").value("1000.00"))
                .andExpect(jsonPath("$.lines[0].yearToDate").value("6000.00"))
                .andExpect(jsonPath("$.netProfitCurrent").value("400.00"))
                .andExpect(jsonPath("$.netProfitYtd").value("2400.00"));
    }

    @Test
    void 利润表_缺period_400() throws Exception {
        mockMvc.perform(get("/api/reports/income-statement"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(service);
    }

    @Test
    void 利润表_period格式非法_400() throws Exception {
        mockMvc.perform(get("/api/reports/income-statement?period=20260"))
                .andExpect(status().isBadRequest());
        Mockito.verifyNoInteractions(service);
    }

    /** period 前后空白经 strip 后仍合法 → 透传 strip 后的值给 service。 */
    @Test
    void 利润表_period含空白_strip后透传() throws Exception {
        Mockito.when(service.incomeStatement("202606")).thenReturn(incomeStmt());

        mockMvc.perform(get("/api/reports/income-statement").param("period", " 202606 "))
                .andExpect(status().isOk());

        Mockito.verify(service).incomeStatement("202606");
    }
}
