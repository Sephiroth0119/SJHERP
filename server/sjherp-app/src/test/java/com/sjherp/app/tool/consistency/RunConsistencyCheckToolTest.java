package com.sjherp.app.tool.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.consistency.ConsistencyBreak;
import com.sjherp.app.consistency.ConsistencyCheckService;
import com.sjherp.app.consistency.ConsistencyCheckType;
import com.sjherp.app.consistency.ConsistencyReport;
import com.sjherp.app.consistency.ConsistencySeverity;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;

/**
 * run_consistency_check 工具单测（M3-T13）：风险级别/权限点/名称声明、空入参经
 * JsonSchemaToolArgumentValidator 合法、含 break 报告映射的计数与精简明细、clean 报告成功且 0 break、
 * 明细截断（超 MAX_ITEMS 只列前 N + truncated）。
 */
class RunConsistencyCheckToolTest {

    private ConsistencyCheckService service;
    private RunConsistencyCheckTool tool;
    private final ToolContext context = new ToolContext("session-1", "1", "帮我核一下账");

    @BeforeEach
    void setUp() {
        service = mock(ConsistencyCheckService.class);
        tool = new RunConsistencyCheckTool(service);
    }

    @Test
    void 风险级别NORMAL_无权限点_名称正确() {
        assertThat(tool.name()).isEqualTo("run_consistency_check");
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isNull();
    }

    @Test
    void 空入参经schema校验合法() {
        JsonSchemaToolArgumentValidator validator = new JsonSchemaToolArgumentValidator();
        List<String> errors = validator.validate(tool.parameterSchema(), Map.of());
        assertThat(errors).isEmpty();
    }

    @Test
    void 多余入参被schema拒绝() {
        JsonSchemaToolArgumentValidator validator = new JsonSchemaToolArgumentValidator();
        List<String> errors = validator.validate(tool.parameterSchema(), Map.of("foo", "bar"));
        assertThat(errors).isNotEmpty();
    }

    @Test
    void 干净报告_成功且0break() {
        when(service.check()).thenReturn(new ConsistencyReport(Instant.now(), List.of()));

        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data())
                .containsEntry("clean", true)
                .containsEntry("breakCount", 0L);
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) result.data().get("severityCounts");
        assertThat(counts).containsEntry("ERROR", 0L).containsEntry("WARN", 0L).containsEntry("INFO", 0L);
        assertThat(result.data()).doesNotContainKey("breaks");
    }

    @Test
    void 含break报告_计数与明细映射() {
        ConsistencyBreak err = ConsistencyBreak.of(ConsistencyCheckType.LEDGER_COST,
                "warehouse=1,product=2", new BigDecimal("2000.00"), new BigDecimal("1999.99"),
                ConsistencySeverity.ERROR, "库存金额恒等式破坏");
        ConsistencyBreak warn = ConsistencyBreak.of(ConsistencyCheckType.SALES_THREE_WAY,
                "SO-1,product=2", new BigDecimal("100"), new BigDecimal("110"),
                ConsistencySeverity.WARN, "销售已开票量超过已发量");
        when(service.check()).thenReturn(new ConsistencyReport(Instant.now(), List.of(err, warn)));

        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data())
                .containsEntry("clean", false)
                .containsEntry("breakCount", 2L);
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) result.data().get("severityCounts");
        assertThat(counts).containsEntry("ERROR", 1L).containsEntry("WARN", 1L).containsEntry("INFO", 0L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> breaks = (List<Map<String, Object>>) result.data().get("breaks");
        assertThat(breaks).hasSize(2);
        assertThat(breaks.get(0))
                .containsEntry("checkType", "库存金额恒等式")
                .containsEntry("expected", "2000.00")
                .containsEntry("actual", "1999.99")
                .containsEntry("severity", "ERROR");
        assertThat(result.data()).doesNotContainKey("truncated");
    }

    @Test
    void break超上限_明细截断并标注truncated() {
        List<ConsistencyBreak> many = new java.util.ArrayList<>();
        int count = ArchiveToolSupport.MAX_ITEMS + 5;
        for (int i = 0; i < count; i++) {
            many.add(ConsistencyBreak.of(ConsistencyCheckType.NEGATIVE_BALANCE,
                    "warehouse=1,product=" + i, BigDecimal.ZERO, new BigDecimal("-1"),
                    ConsistencySeverity.ERROR, "负库存"));
        }
        when(service.check()).thenReturn(new ConsistencyReport(Instant.now(), many));

        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("breakCount", (long) count);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> breaks = (List<Map<String, Object>>) result.data().get("breaks");
        assertThat(breaks).hasSize(ArchiveToolSupport.MAX_ITEMS);
        assertThat(result.data()).containsEntry("truncated", (long) (count - ArchiveToolSupport.MAX_ITEMS));
    }

    @Test
    void 服务抛异常转失败结果() {
        when(service.check()).thenThrow(new RuntimeException("DB 连接超时"));

        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("一致性校验执行失败").contains("DB 连接超时");
    }
}
