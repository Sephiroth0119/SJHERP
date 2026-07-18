package com.sjherp.app.tool.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.consistency.ConsistencyCheckRunner;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.consistency.ConsistencyCheckRun;
import com.sjherp.domain.consistency.ConsistencyFinding;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;

class RunConsistencyCheckToolTest {

    private static final Instant NOW = Instant.parse("2026-07-19T01:02:03Z");
    private ConsistencyCheckRunner runner;
    private RunConsistencyCheckTool tool;
    private final ToolContext context = new ToolContext("session-1", "7", "帮我核一下账");

    @BeforeEach
    void setUp() {
        runner = mock(ConsistencyCheckRunner.class);
        tool = new RunConsistencyCheckTool(runner);
    }

    @Test
    void keepsLoginOnlyNormalRiskAndDeclaresSeventeenDeterministicRuleCategories() {
        assertThat(tool.name()).isEqualTo("run_consistency_check");
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isNull();
        assertThat(tool.description()).contains("17 类确定性规则");
    }

    @Test
    void emptyArgumentsPassSchemaAndAdditionalArgumentsFail() {
        JsonSchemaToolArgumentValidator validator = new JsonSchemaToolArgumentValidator();
        assertThat(validator.validate(tool.parameterSchema(), Map.of())).isEmpty();
        assertThat(validator.validate(tool.parameterSchema(), Map.of("foo", "bar"))).isNotEmpty();
    }

    @Test
    void executesRunnerAsTrustedContextUserAndReturnsRunNumber() {
        when(runner.runAgent("7")).thenReturn(run(List.of()));

        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data())
                .containsEntry("runNo", "CHK-202607-0001")
                .containsEntry("clean", true)
                .containsEntry("breakCount", 0L);
        verify(runner).runAgent("7");
    }

    @Test
    void mapsCountsPlainDecimalsAndCapsFindingDetails() {
        List<ConsistencyFinding> findings = new ArrayList<>();
        int count = ArchiveToolSupport.MAX_ITEMS + 5;
        for (int i = 1; i <= count; i++) {
            findings.add(new ConsistencyFinding(i, "CORE_SQL_ASSERTIONS", "LEDGER_COST",
                    "warehouse=1,product=" + i, new BigDecimal("10.123456"),
                    new BigDecimal("9.000000"), ConsistencyFinding.Severity.ERROR,
                    "库存金额恒等式破坏"));
        }
        when(runner.runAgent("7")).thenReturn(run(findings));

        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("breakCount", (long) count)
                .containsEntry("truncated", (long) (count - ArchiveToolSupport.MAX_ITEMS));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> breaks = (List<Map<String, Object>>) result.data().get("breaks");
        assertThat(breaks).hasSize(ArchiveToolSupport.MAX_ITEMS);
        assertThat(breaks.get(0))
                .containsEntry("expected", "10.123456")
                .containsEntry("actual", "9.000000")
                .containsEntry("severity", "ERROR");
    }

    @Test
    void failureUsesGenericMessageWithoutLeakingExceptionText() {
        when(runner.runAgent("7")).thenThrow(new RuntimeException("jdbc:secret-password"));

        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("一致性校验执行失败，请稍后重试或联系管理员")
                .doesNotContain("jdbc:secret-password");
    }

    private static ConsistencyCheckRun run(List<ConsistencyFinding> findings) {
        return ConsistencyCheckRun.completed(0, "CHK-202607-0001",
                ConsistencyCheckRun.TriggerType.AGENT, "agent:7", NOW, NOW,
                ConsistencyCheckRun.AnalysisStatus.SKIPPED, null, findings);
    }
}
