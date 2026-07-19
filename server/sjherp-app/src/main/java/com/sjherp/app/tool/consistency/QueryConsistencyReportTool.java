package com.sjherp.app.tool.consistency;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.consistency.ConsistencyReportNotFoundException;
import com.sjherp.app.consistency.ConsistencyReportService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.consistency.ConsistencyCheckRun;
import com.sjherp.domain.consistency.ConsistencyFinding;

/** 只读一致性历史报告召回工具（M6-T07，所有已登录用户可用）。 */
public class QueryConsistencyReportTool implements Tool {

    public static final String NAME = "query_consistency_report";
    private static final int MAX_DAYS_AGO = 365;

    private final ConsistencyReportService reports;
    private final Clock clock;

    public QueryConsistencyReportTool(ConsistencyReportService reports) {
        this(reports, Clock.systemUTC());
    }

    QueryConsistencyReportTool(ConsistencyReportService reports, Clock clock) {
        this.reports = Objects.requireNonNull(reports, "reports 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "召回历史数据一致性检查报告并帮助解释。用户问“昨天的数据检查结果”“上次检查有没有问题”"
                + "或提供 CHK- 运行编号时调用；不重新跑检查、不修改业务账。可按 runNo、date（UTC 的 YYYY-MM-DD）"
                + "或 daysAgo（0=今天，1=昨天）查询，均不填时返回最近一次报告。报告含安全摘要与最多 "
                + ArchiveToolSupport.MAX_ITEMS + " 条差异明细；必须如实说明 ERROR/WARN，不能声称已自动修复。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{
                "runNo":{"type":"string","description":"运行编号，如 CHK-202607-0001（可选）"},
                "date":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}$","description":"UTC 自然日 YYYY-MM-DD（可选）"},
                "daysAgo":{"type":"integer","minimum":0,"maximum":365,"description":"相对今天的天数，0=今天、1=昨天（可选）"}
                },"required":[],"additionalProperties":false}
                """;
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.NORMAL;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        try {
            Map<String, Object> args = arguments == null ? Map.of() : arguments;
            String runNo = ArchiveToolSupport.str(args.get("runNo"));
            String dateText = ArchiveToolSupport.str(args.get("date"));
            Object daysAgoRaw = args.get("daysAgo");
            if (runNo != null && (dateText != null || daysAgoRaw != null)) {
                return ToolResult.fail("runNo 不能与 date 或 daysAgo 同时使用");
            }
            Optional<ConsistencyCheckRun> result;
            String queryLabel;
            if (runNo != null) {
                try {
                    result = Optional.of(reports.get(runNo));
                } catch (ConsistencyReportNotFoundException notFound) {
                    return ToolResult.fail("未找到运行编号为 " + runNo + " 的一致性报告");
                }
                queryLabel = runNo;
            } else if (dateText != null) {
                LocalDate date = parseDate(dateText);
                result = reports.latestOn(date);
                queryLabel = date.toString() + "（UTC）";
            } else if (daysAgoRaw != null) {
                int daysAgo = parseDaysAgo(daysAgoRaw);
                result = reports.latestOn(LocalDate.now(clock).minusDays(daysAgo));
                queryLabel = daysAgo == 0 ? "今天（UTC）" : daysAgo + " 天前（UTC）";
            } else {
                result = reports.latest();
                queryLabel = "最近一次";
            }
            return result.map(run -> ToolResult.ok(toData(run, queryLabel)))
                    .orElseGet(() -> ToolResult.fail("没有找到" + queryLabel + "的一致性检查报告"));
        } catch (IllegalArgumentException | DateTimeException invalid) {
            return ToolResult.fail(invalid.getMessage());
        } catch (RuntimeException failure) {
            return ToolResult.fail("一致性报告召回失败，请稍后重试或联系管理员");
        }
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("date 日期格式错误，须为 YYYY-MM-DD");
        }
    }

    private static int parseDaysAgo(Object raw) {
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException("daysAgo 须为 0-365 的整数");
        }
        int daysAgo = number.intValue();
        if (number.doubleValue() != daysAgo || daysAgo < 0 || daysAgo > MAX_DAYS_AGO) {
            throw new IllegalArgumentException("daysAgo 须为 0-365 的整数");
        }
        return daysAgo;
    }

    private static Map<String, Object> toData(ConsistencyCheckRun run, String queryLabel) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", queryLabel);
        data.put("found", true);
        data.put("runNo", run.runNo());
        data.put("triggerType", run.triggerType().name());
        data.put("checkedAt", run.completedAt().toString());
        data.put("status", run.status().name());
        data.put("clean", run.clean());
        data.put("severityCounts", Map.of("ERROR", run.errorCount(), "WARN", run.warnCount(),
                "INFO", run.infoCount()));
        data.put("explanation", explanation(run));
        if (run.analysisSummary() != null) {
            data.put("analysisSummary", run.analysisSummary());
        }
        if (run.failureType() != null) {
            data.put("failureType", run.failureType());
        }
        List<Map<String, Object>> findings = new ArrayList<>();
        int shown = 0;
        for (ConsistencyFinding finding : run.findings()) {
            if (shown++ >= ArchiveToolSupport.MAX_ITEMS) {
                break;
            }
            findings.add(Map.ofEntries(
                    Map.entry("sequenceNo", finding.sequenceNo()),
                    Map.entry("ruleCode", finding.ruleCode()),
                    Map.entry("checkType", finding.checkType()),
                    Map.entry("key", Objects.toString(finding.objectKey(), "")),
                    Map.entry("expected", Objects.toString(plain(finding.expectedValue()), "")),
                    Map.entry("actual", Objects.toString(plain(finding.actualValue()), "")),
                    Map.entry("severity", finding.severity().name()),
                    Map.entry("message", Objects.toString(finding.message(), ""))));
        }
        data.put("findings", findings);
        if (run.totalCount() > ArchiveToolSupport.MAX_ITEMS) {
            data.put("truncated", run.totalCount() - ArchiveToolSupport.MAX_ITEMS);
        }
        return data;
    }

    private static String explanation(ConsistencyCheckRun run) {
        if (run.status() == ConsistencyCheckRun.Status.FAILED) {
            return "这次检查未完成，不能据此判断账实一致；应稍后重试或联系管理员。";
        }
        if (run.clean()) {
            return "检查已完成，未发现 ERROR、WARN 或 INFO 差异；这表示本次规则范围内账实一致，不代表未覆盖的业务事实。";
        }
        if (run.errorCount() > 0) {
            return "检查已完成但存在 P0 ERROR，系统只上报未自动修复；请按差异明细核对对应业务单据，并通过业务冲销/更正流程处理。";
        }
        return "检查已完成，未发现 P0 ERROR，但存在需要关注的 WARN/INFO；请结合差异明细判断是否需要业务处理。";
    }

    private static String plain(java.math.BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
