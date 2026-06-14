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
 * query_receivable_settlements 工具单测（M4-T08）：name/riskLevel=NORMAL/requiredPermission=finance:settlement/
 * parameterSchema（receivableId 必填）/execute 调 SettlementReadAppService.findReceivableSettlements
 * （mock，verify id 透传）/返回结构含 targetId/count/records（amount toPlainString/type.name()）/
 * receivableId 缺失/非整数/≤0 前置 fail 不调服务。
 */
class QueryReceivableSettlementsToolTest {

    private SettlementReadAppService settlementReadAppService;
    private QueryReceivableSettlementsTool tool;
    private final ToolContext context = new ToolContext("session-rs", "6", "查应收核销记录");

    @BeforeEach
    void setUp() {
        settlementReadAppService = mock(SettlementReadAppService.class);
        tool = new QueryReceivableSettlementsTool(settlementReadAppService);
    }

    // ================================================================== 元数据

    @Test
    void name() {
        assertThat(tool.name()).isEqualTo("query_receivable_settlements");
    }

    @Test
    void 风险级别NORMAL_权限点finance_settlement() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isEqualTo("finance:settlement");
    }

    @Test
    void parameterSchema_receivableId必填() {
        String schema = tool.parameterSchema();
        assertThat(schema).contains("\"receivableId\"");
        assertThat(schema).contains("\"required\":[\"receivableId\"]");
        assertThat(schema).contains("\"additionalProperties\":false");
    }

    @Test
    void schema校验_合法receivableId通过() {
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(), Map.of("receivableId", 42));
        assertThat(errors).isEmpty();
    }

    // ================================================================== execute 成功

    @Test
    void 指定receivableId_参数透传_返回核销记录列表() {
        List<SettlementRecord> records = List.of(stubRecord(1001L));
        when(settlementReadAppService.findReceivableSettlements(eq(42L)))
                .thenReturn(records);

        ToolResult result = tool.execute(Map.of("receivableId", 42), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("targetId")).isEqualTo(42L);
        assertThat(result.data().get("count")).isEqualTo(1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recs =
                (List<Map<String, Object>>) result.data().get("records");
        assertThat(recs).hasSize(1);
        Map<String, Object> rec = recs.get(0);
        // amount 为 toPlainString 字符串
        assertThat(rec.get("amount")).isEqualTo("1500.00");
        // type 为 enum.name()
        assertThat(rec.get("type")).isEqualTo("RECEIVABLE");
        assertThat(rec.get("targetSourceDocNo")).isEqualTo("SINV-2026001");
        assertThat(rec.get("settlementDate")).isNotNull();
        assertThat(rec.get("paymentDocNo")).isEqualTo("RCPT-2026001");

        verify(settlementReadAppService).findReceivableSettlements(eq(42L));
    }

    @Test
    void receivableId_Long类型_也支持() {
        when(settlementReadAppService.findReceivableSettlements(eq(99L)))
                .thenReturn(List.of());

        ToolResult result = tool.execute(Map.of("receivableId", 99L), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("count")).isEqualTo(0);
        verify(settlementReadAppService).findReceivableSettlements(eq(99L));
    }

    @Test
    void 无核销记录时返回空列表_count为0() {
        when(settlementReadAppService.findReceivableSettlements(eq(10L)))
                .thenReturn(List.of());

        ToolResult result = tool.execute(Map.of("receivableId", 10), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("count")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<?> recs = (List<?>) result.data().get("records");
        assertThat(recs).isEmpty();
    }

    // ================================================================== execute 失败（前置 fail）

    @Test
    void receivableId缺失_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("receivableId");
        verifyNoInteractions(settlementReadAppService);
    }

    @Test
    void receivableId为非整数字符串_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("receivableId", "abc"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("receivableId");
        verifyNoInteractions(settlementReadAppService);
    }

    @Test
    void receivableId为零_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("receivableId", 0), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("receivableId");
        verifyNoInteractions(settlementReadAppService);
    }

    @Test
    void receivableId为负数_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("receivableId", -1), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("receivableId");
        verifyNoInteractions(settlementReadAppService);
    }

    // ================================================================== 辅助方法

    private static SettlementRecord stubRecord(long id) {
        return SettlementRecord.restore(
                id,
                SettlementType.RECEIVABLE,
                42L,
                "SINV-2026001",
                new BigDecimal("1500.00"),
                LocalDate.of(2026, 6, 15),
                "RCPT-2026001",
                "operator1",
                Instant.parse("2026-06-15T08:00:00Z"));
    }
}
