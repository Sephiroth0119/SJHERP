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
 * query_receivable_aging 工具单测（M4-T08）：name/riskLevel=NORMAL/requiredPermission=finance:settlement/
 * parameterSchema（asOf pattern、无必填）/execute 调 AgingReportDao.receivableAging（mock，verify 参数透传：
 * asOf 缺省今天/customerId 可选/page/size）/返回结构含 5 桶金额字符串与 grandTotal/
 * asOf 格式错前置 fail 不调服务（verifyNoInteractions）/customerId 非整数前置 fail。
 */
class QueryReceivableAgingToolTest {

    private AgingReportDao agingReportDao;
    private QueryReceivableAgingTool tool;
    private final ToolContext context = new ToolContext("session-ar", "2", "查应收账龄");

    @BeforeEach
    void setUp() {
        agingReportDao = mock(AgingReportDao.class);
        tool = new QueryReceivableAgingTool(agingReportDao);
    }

    // ================================================================== 元数据

    @Test
    void name() {
        assertThat(tool.name()).isEqualTo("query_receivable_aging");
    }

    @Test
    void 风险级别NORMAL_权限点finance_settlement() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isEqualTo("finance:settlement");
    }

    @Test
    void parameterSchema_asOf_pattern_和页码字段存在_无必填项() {
        String schema = tool.parameterSchema();
        assertThat(schema).contains("\"asOf\"");
        assertThat(schema).contains("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
        assertThat(schema).contains("\"customerId\"");
        assertThat(schema).contains("\"page\"");
        assertThat(schema).contains("\"size\"");
        // required 为空数组
        assertThat(schema).contains("\"required\":[]");
        assertThat(schema).contains("additionalProperties\":false");
    }

    @Test
    void schema校验_空参合法() {
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(), Map.of());
        assertThat(errors).isEmpty();
    }

    // ================================================================== execute 成功

    @Test
    void 指定asOf和customerId_参数透传_返回账龄5桶与grandTotal() {
        LocalDate asOf = LocalDate.of(2026, 6, 30);
        AgingReport report = stubReport(asOf, 101L);
        when(agingReportDao.receivableAging(eq(asOf), eq(101L), eq(1), eq(20)))
                .thenReturn(report);

        ToolResult result = tool.execute(
                Map.of("asOf", "2026-06-30", "customerId", 101), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsKey("asOf");
        assertThat(result.data()).containsKey("rows");
        assertThat(result.data()).containsKey("grandTotal");
        assertThat(result.data()).containsKey("pagination");

        // 验证行数据金额为字符串
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.data().get("rows");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("counterpartyId")).isEqualTo(101L);
        assertThat(row.get("notDue")).isEqualTo("1000.00");
        assertThat(row.get("overdue1To30")).isEqualTo("200.00");
        assertThat(row.get("overdue31To60")).isEqualTo("0.00");
        assertThat(row.get("overdue61To90")).isEqualTo("0.00");
        assertThat(row.get("overdue90Plus")).isEqualTo("0.00");
        assertThat(row.get("totalOutstanding")).isEqualTo("1200.00");

        // grandTotal 也是字符串
        @SuppressWarnings("unchecked")
        Map<String, Object> gt = (Map<String, Object>) result.data().get("grandTotal");
        assertThat(gt.get("totalOutstanding")).isEqualTo("1200.00");

        verify(agingReportDao).receivableAging(eq(asOf), eq(101L), eq(1), eq(20));
    }

    @Test
    void 不传asOf缺省今天_不传customerId传null() {
        LocalDate today = LocalDate.now();
        AgingReport report = stubReport(today, null);
        when(agingReportDao.receivableAging(eq(today), isNull(), eq(1), eq(20)))
                .thenReturn(report);

        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isTrue();
        verify(agingReportDao).receivableAging(eq(today), isNull(), eq(1), eq(20));
    }

    @Test
    void 自定义page和size_透传() {
        LocalDate asOf = LocalDate.of(2026, 6, 1);
        AgingReport report = stubReport(asOf, null);
        when(agingReportDao.receivableAging(eq(asOf), isNull(), eq(2), eq(50)))
                .thenReturn(report);

        ToolResult result = tool.execute(
                Map.of("asOf", "2026-06-01", "page", 2, "size", 50), context);

        assertThat(result.success()).isTrue();
        verify(agingReportDao).receivableAging(eq(asOf), isNull(), eq(2), eq(50));
    }

    // ================================================================== execute 失败（前置 fail）

    @Test
    void asOf格式错误_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("asOf", "20260630"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("asOf");
        verifyNoInteractions(agingReportDao);
    }

    @Test
    void customerId非整数_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("customerId", "abc"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("customerId");
        verifyNoInteractions(agingReportDao);
    }

    // ================================================================== 辅助方法

    private static AgingReport stubReport(LocalDate asOf, Long counterpartyId) {
        long cpId = counterpartyId != null ? counterpartyId : 1L;
        // 使用显式字符串构造 BigDecimal，确保 toPlainString() 返回带小数点的字符串（"0.00" 而非 "0"）
        AgingRow row = new AgingRow(cpId, "C-001", "测试客户",
                new BigDecimal("1000.00"), new BigDecimal("200.00"),
                new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                new BigDecimal("1200.00"));
        AgingGrandTotal gt = new AgingGrandTotal(
                new BigDecimal("1000.00"), new BigDecimal("200.00"),
                new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                new BigDecimal("1200.00"));
        return new AgingReport(asOf, new PageResult<>(List.of(row), 1L, 1, 20), gt);
    }
}
