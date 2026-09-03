package com.sjherp.app.tool.consistency;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.consistency.ConsistencyCheckRunner;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.consistency.ConsistencyCheckRun;
import com.sjherp.domain.consistency.ConsistencyFinding;

/**
 * 数据一致性校验工具（M6-T05 检查 Agent，NORMAL）：显式运行并持久化检查报告。
 *
 * <p>不改业务账、登录即可（{@link #requiredPermission()} 返回 null），但会只追加保存运行报告并在有差异时
 * 通知管理员/老板；不走 HITL 确认。用户问「账对不对/有没有对不平/库存-成本-应收应付有没有差错/帮我核一下账」时调用。
 *
 * <p>返回总 break 数、按严重度分组计数、精简明细（最多 {@link ArchiveToolSupport#MAX_ITEMS} 条，
 * 避免灌爆上下文，金额数量 toPlainString 承载）；0 break 时明确回复账已对平。
 * 检查不改账，纠错走业务单据。
 */
public class RunConsistencyCheckTool implements Tool {

    public static final String NAME = "run_consistency_check";

    private final ConsistencyCheckRunner consistencyCheckRunner;

    public RunConsistencyCheckTool(ConsistencyCheckRunner consistencyCheckRunner) {
        this.consistencyCheckRunner = Objects.requireNonNull(consistencyCheckRunner,
                "consistencyCheckRunner 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "运行数据一致性交叉校验并保存报告。用户问\"账对不对/有没有对不平/库存-成本-应收应付"
                + "有没有差错/帮我核一下账\"时调用。检查 17 类确定性规则，返回运行编号、总差异数、"
                + "按严重度(ERROR/WARN/INFO)分组计数与精简明细；0 break 即账已对平。"
                + "检查不改账，发现问题的纠错走业务单据红字冲销。";
    }

    @Override
    public String parameterSchema() {
        // 无入参：空对象
        return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        try {
            ConsistencyCheckRun run = consistencyCheckRunner.runAgent(context.userId());
            return ToolResult.ok(toData(run));
        } catch (RuntimeException e) {
            return ToolResult.fail("一致性校验执行失败，请稍后重试或联系管理员");
        }
    }

    private static Map<String, Object> toData(ConsistencyCheckRun run) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runNo", run.runNo());
        data.put("checkedAt", run.completedAt().toString());
        data.put("clean", run.clean());
        long total = run.totalCount();
        data.put("breakCount", total);
        Map<String, Object> bySeverity = new LinkedHashMap<>();
        bySeverity.put("ERROR", run.errorCount());
        bySeverity.put("WARN", run.warnCount());
        bySeverity.put("INFO", run.infoCount());
        data.put("severityCounts", bySeverity);

        if (run.clean()) {
            data.put("summary", "账已对平（0 break）");
            return data;
        }
        data.put("summary", "发现 " + total + " 处对不上：ERROR " + run.errorCount()
                + " / WARN " + run.warnCount() + " / INFO " + run.infoCount()
                + "（仅列前 " + ArchiveToolSupport.MAX_ITEMS + " 条；运行编号 " + run.runNo() + "）");
        List<Map<String, Object>> items = new ArrayList<>();
        int shown = 0;
        for (ConsistencyFinding finding : run.findings()) {
            if (shown++ >= ArchiveToolSupport.MAX_ITEMS) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("checkType", finding.checkType());
            row.put("key", finding.objectKey());
            row.put("expected", plain(finding.expectedValue()));
            row.put("actual", plain(finding.actualValue()));
            row.put("severity", finding.severity().name());
            row.put("message", finding.message());
            items.add(row);
        }
        data.put("breaks", items);
        if (total > ArchiveToolSupport.MAX_ITEMS) {
            data.put("truncated", total - ArchiveToolSupport.MAX_ITEMS);
        }
        return data;
    }

    private static String plain(java.math.BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
