package com.sjherp.app.tool.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.settlement.SettlementReadAppService;
import com.sjherp.domain.settlement.SettlementRecord;
import com.sjherp.domain.settlement.SettlementType;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;

/**
 * query_payable_settlements 工具单测（M4-T08）：name/riskLevel=NORMAL/requiredPermission=finance:settlement/
 * parameterSchema（payableId 必填）/execute 调 SettlementReadAppService.findPayableSettlements
 * （mock，verify id 透传，委托 QueryReceivableSettlementsTool.toData）/返回结构含 targetId/count/records/
 * payableId 缺失/非整数/≤0 前置 fail 不调服务（verifyNoInteractions）。
 */
class QueryPayableSettlementsToolTest {

    private SettlementReadAppService settlementReadAppService;
    private QueryPayableSettlementsTool tool;
    private final ToolContext context = new ToolContext("session-ps", "7", "查应付核销记录");

    @BeforeEach
    void setUp() {
        settlementReadAppService = mock(SettlementReadAppService.class);
        tool = new QueryPayableSettlementsTool(settlementReadAppService);
    }

    // ================================================================== 元数据

    @Test
    void name() {
        assertThat(tool.name()).isEqualTo("query_payable_settlements");
    }

    @Test
    void 风险级别NORMAL_权限点finance_settlement() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isEqualTo("finance:settlement");
    }

    @Test
    void parameterSchema_payableId必填() {
        String schema = tool.parameterSchema();
        assertThat(schema).contains("\"payableId\"");
        assertThat(schema).contains("\"required\":[\"payableId\"]");
        assertThat(schema).contains("\"additionalProperties\":false");
    }

    @Test
    void schema校验_合法payableId通过() {
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(), Map.of("payableId", 77));
        assertThat(errors).isEmpty();
    }

    // ================================================================== execute 成功

    @Test
    void 指定payableId_参数透传_返回核销记录列表_type为PAYABLE() {
        List<SettlementRecord> records = List.of(stubRecord(2001L));
        when(settlementReadAppService.findPayableSettlements(eq(77L)))
                .thenReturn(records);

        ToolResult result = tool.execute(Map.of("payableId", 77), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("targetId")).isEqualTo(77L);
        assertThat(result.data().get("count")).isEqualTo(1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recs =
                (List<Map<String, Object>>) result.data().get("records");
        assertThat(recs).hasSize(1);
        // type 使用 enum.name()
        assertThat(recs.get(0).get("type")).isEqualTo("PAYABLE");
        assertThat(recs.get(0).get("amount")).isEqualTo("3000.00");
        assertThat(recs.get(0).get("paymentDocNo")).isEqualTo("PAYV-2026001");

        verify(settlementReadAppService).findPayableSettlements(eq(77L));
    }

    @Test
    void 无核销记录时返回空列表() {
        when(settlementReadAppService.findPayableSettlements(eq(5L)))
                .thenReturn(List.of());

        ToolResult result = tool.execute(Map.of("payableId", 5), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("count")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<?> recs = (List<?>) result.data().get("records");
        assertThat(recs).isEmpty();
    }

    // ================================================================== execute 失败（前置 fail）

    @Test
    void payableId缺失_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("payableId");
        verifyNoInteractions(settlementReadAppService);
    }

    @Test
    void payableId非整数_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("payableId", "xyz"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("payableId");
        verifyNoInteractions(settlementReadAppService);
    }

    @Test
    void payableId为零_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("payableId", 0), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("payableId");
        verifyNoInteractions(settlementReadAppService);
    }

    @Test
    void payableId为负数_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("payableId", -5), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("payableId");
        verifyNoInteractions(settlementReadAppService);
    }

    // ================================================================== 辅助方法

    private static SettlementRecord stubRecord(long id) {
        return SettlementRecord.restore(
                id,
                SettlementType.PAYABLE,
                77L,
                "PINV-2026001",
                new BigDecimal("3000.00"),
                LocalDate.of(2026, 6, 20),
                "PAYV-2026001",
                "accountant1",
                Instant.parse("2026-06-20T09:00:00Z"));
    }
}
