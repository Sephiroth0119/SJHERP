package com.sjherp.app.tool.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.consistency.ConsistencyReportService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.consistency.ConsistencyCheckRun;
import com.sjherp.domain.consistency.ConsistencyFinding;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;

class QueryConsistencyReportToolTest {

    private static final Instant NOW = Instant.parse("2026-07-19T01:02:03Z");
    private final ConsistencyReportService reports = mock(ConsistencyReportService.class);
    private final QueryConsistencyReportTool tool = new QueryConsistencyReportTool(
            reports, Clock.fixed(NOW, ZoneOffset.UTC));
    private final ToolContext context = new ToolContext("session-1", "7", "昨天的数据检查结果");

    @Test
    void isLoginOnlyNormalReadToolWithValidBoundedSchema() {
        assertThat(tool.name()).isEqualTo("query_consistency_report");
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isNull();
        assertThat(new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(), Map.of("daysAgo", 1))).isEmpty();
    }

    @Test
    void recallsYesterdayAndReturnsExplanationAndPlainDecimalFindings() {
        ConsistencyCheckRun report = report();
        when(reports.latestOn(LocalDate.of(2026, 7, 18))).thenReturn(java.util.Optional.of(report));

        ToolResult result = tool.execute(Map.of("daysAgo", 1), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("runNo", "CHK-202607-0001")
                .containsEntry("clean", false)
                .containsEntry("explanation", "检查已完成但存在 P0 ERROR，系统只上报未自动修复；请按差异明细核对对应业务单据，并通过业务冲销/更正流程处理。");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) result.data().get("findings");
        assertThat(findings).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("expected", "10.123456")
                .containsEntry("actual", "9.000000")
                .containsEntry("severity", "ERROR"));
        verify(reports).latestOn(LocalDate.of(2026, 7, 18));
    }

    @Test
    void capsDetailsAndDoesNotLeakMissingReportException() {
        List<ConsistencyFinding> findings = java.util.stream.IntStream.rangeClosed(
                        1, ArchiveToolSupport.MAX_ITEMS + 1)
                .mapToObj(i -> new ConsistencyFinding(i, "CORE_SQL_ASSERTIONS", "LEDGER_COST",
                        "key=" + i, BigDecimal.ONE, BigDecimal.ZERO,
                        ConsistencyFinding.Severity.ERROR, "差异"))
                .toList();
        ConsistencyCheckRun report = ConsistencyCheckRun.completed(0, "CHK-202607-0002",
                ConsistencyCheckRun.TriggerType.SCHEDULED, "system:consistency-scheduler", NOW, NOW,
                ConsistencyCheckRun.AnalysisStatus.SKIPPED, null, findings);
        when(reports.latest()).thenReturn(java.util.Optional.of(report));

        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.data().get("findings");
        assertThat(rows).hasSize(ArchiveToolSupport.MAX_ITEMS);
        assertThat(result.data()).containsEntry("truncated", 1L);
    }

    @Test
    void rejectsConflictingSelectorsAndInvalidRelativeDay() {
        assertThat(tool.execute(Map.of("runNo", "CHK-1", "daysAgo", 1), context).error())
                .isEqualTo("runNo、date、daysAgo 只能选择一个");
        assertThat(tool.execute(Map.of("date", "2026-07-18", "daysAgo", 1), context).error())
                .isEqualTo("runNo、date、daysAgo 只能选择一个");
        assertThat(tool.execute(Map.of("daysAgo", 366), context).error())
                .isEqualTo("daysAgo 须为 0-365 的整数");
    }

    private static ConsistencyCheckRun report() {
        return ConsistencyCheckRun.completed(0, "CHK-202607-0001",
                ConsistencyCheckRun.TriggerType.SCHEDULED, "system:consistency-scheduler", NOW, NOW,
                ConsistencyCheckRun.AnalysisStatus.SKIPPED, null,
                List.of(new ConsistencyFinding(1, "CORE_SQL_ASSERTIONS", "LEDGER_COST",
                        "warehouse=1,product=2", new BigDecimal("10.123456"),
                        new BigDecimal("9.000000"), ConsistencyFinding.Severity.ERROR, "库存成本不一致")));
    }
}
