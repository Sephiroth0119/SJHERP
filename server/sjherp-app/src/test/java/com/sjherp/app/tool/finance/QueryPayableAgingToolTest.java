package com.sjherp.app.tool.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.finance.AgingReportDao;
import com.sjherp.app.finance.AgingReportDao.AgingGrandTotal;
import com.sjherp.app.finance.AgingReportDao.AgingReport;
import com.sjherp.app.finance.AgingReportDao.AgingRow;
import com.sjherp.domain.common.PageResult;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;

/**
 * query_payable_aging 工具单测（M4-T08）：name/riskLevel=NORMAL/requiredPermission=finance:settlement/
 * parameterSchema（asOf pattern、supplierId、page/size，无必填）/execute 调 AgingReportDao.payableAging
 * （mock，verify 参数透传：asOf 缺省今天/supplierId 可选）/返回结构含 5 桶金额字符串与 grandTotal/
 * asOf 格式错前置 fail 不调服务/supplierId 非整数前置 fail。
 */
class QueryPayableAgingToolTest {

    private AgingReportDao agingReportDao;
    private QueryPayableAgingTool tool;
    private final ToolContext context = new ToolContext("session-ap", "3", "查应付账龄");

    @BeforeEach
    void setUp() {
        agingReportDao = mock(AgingReportDao.class);
        tool = new QueryPayableAgingTool(agingReportDao);
    }

    // ================================================================== 元数据

    @Test
    void name() {
        assertThat(tool.name()).isEqualTo("query_payable_aging");
    }

    @Test
    void 风险级别NORMAL_权限点finance_settlement() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isEqualTo("finance:settlement");
    }

    @Test
    void parameterSchema_supplierId字段存在_无必填项() {
        String schema = tool.parameterSchema();
        assertThat(schema).contains("\"asOf\"");
        assertThat(schema).contains("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
        assertThat(schema).contains("\"supplierId\"");
        assertThat(schema).contains("\"page\"");
        assertThat(schema).contains("\"size\"");
        assertThat(schema).contains("\"required\":[]");
    }

    @Test
    void schema校验_空参合法() {
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(), Map.of());
        assertThat(errors).isEmpty();
    }

    // ================================================================== execute 成功

    @Test
    void 指定asOf和supplierId_参数透传_返回5桶与grandTotal() {
        LocalDate asOf = LocalDate.of(2026, 6, 30);
        AgingReport report = stubReport(asOf, 201L);
        when(agingReportDao.payableAging(eq(asOf), eq(201L), eq(1), eq(20)))
                .thenReturn(report);

        ToolResult result = tool.execute(
                Map.of("asOf", "2026-06-30", "supplierId", 201), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsKey("asOf");
        assertThat(result.data()).containsKey("rows");
        assertThat(result.data()).containsKey("grandTotal");
        assertThat(result.data()).containsKey("pagination");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.data().get("rows");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("notDue")).isEqualTo("500.00");
        assertThat(row.get("overdue1To30")).isEqualTo("100.00");
        assertThat(row.get("totalOutstanding")).isEqualTo("600.00");

        @SuppressWarnings("unchecked")
        Map<String, Object> gt = (Map<String, Object>) result.data().get("grandTotal");
        assertThat(gt.get("totalOutstanding")).isEqualTo("600.00");

        verify(agingReportDao).payableAging(eq(asOf), eq(201L), eq(1), eq(20));
    }

    @Test
    void 不传asOf缺省今天_不传supplierId传null() {
        LocalDate today = LocalDate.now();
        AgingReport report = stubReport(today, null);
        when(agingReportDao.payableAging(eq(today), isNull(), eq(1), eq(20)))
                .thenReturn(report);

        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isTrue();
        verify(agingReportDao).payableAging(eq(today), isNull(), eq(1), eq(20));
    }

    @Test
    void size超过MAX_SIZE_被重置为默认值20() {
        // 实际工具逻辑：size < 1 || size > MAX_SIZE 时重置为 20（默认值），不是截断到 MAX_SIZE
        LocalDate today = LocalDate.now();
        AgingReport report = stubReport(today, null);
        when(agingReportDao.payableAging(eq(today), isNull(), eq(1), eq(20)))
                .thenReturn(report);

        ToolResult result = tool.execute(Map.of("size", 999), context);

        assertThat(result.success()).isTrue();
        verify(agingReportDao).payableAging(eq(today), isNull(), eq(1), eq(20));
    }

    @Test
    void page小于1_被修正为1() {
        LocalDate today = LocalDate.now();
        AgingReport report = stubReport(today, null);
        when(agingReportDao.payableAging(eq(today), isNull(), eq(1), eq(20)))
                .thenReturn(report);

        ToolResult result = tool.execute(Map.of("page", 0), context);

        assertThat(result.success()).isTrue();
        verify(agingReportDao).payableAging(eq(today), isNull(), eq(1), eq(20));
    }

    // ================================================================== execute 失败（前置 fail）

    @Test
    void asOf格式错误_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("asOf", "2026/06/30"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("asOf");
        verifyNoInteractions(agingReportDao);
    }

    @Test
    void supplierId非整数_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("supplierId", "S001"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("supplierId");
        verifyNoInteractions(agingReportDao);
    }

    // ================================================================== 辅助方法

    private static AgingReport stubReport(LocalDate asOf, Long supplierId) {
        long cpId = supplierId != null ? supplierId : 201L;
        // 使用显式字符串构造 BigDecimal，确保 toPlainString() 返回带小数点的字符串
        AgingRow row = new AgingRow(cpId, "S-001", "测试供应商",
                new BigDecimal("500.00"), new BigDecimal("100.00"),
                new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                new BigDecimal("600.00"));
        AgingGrandTotal gt = new AgingGrandTotal(
                new BigDecimal("500.00"), new BigDecimal("100.00"),
                new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                new BigDecimal("600.00"));
        return new AgingReport(asOf, new PageResult<>(List.of(row), 1L, 1, 20), gt);
    }
}
