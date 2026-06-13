package com.sjherp.app.tool.consistency;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.consistency.ConsistencyBreak;
import com.sjherp.app.consistency.ConsistencyCheckService;
import com.sjherp.app.consistency.ConsistencyReport;
import com.sjherp.app.tool.ArchiveToolSupport;

/**
 * 数据一致性校验工具（M3-T13 检查 Agent，NORMAL，只读）：跑七条勾稽校验产出报告。
 *
 * <p>只读、登录即可（{@link #requiredPermission()} 返回 null，照只读查询工具如 query_purchase_order），
 * 不走 HITL 确认。用户问「账对不对/有没有对不平/库存-成本-应收应付有没有差错/帮我核一下账」时调用。
 *
 * <p>返回总 break 数、按严重度分组计数、精简明细（最多 {@link ArchiveToolSupport#MAX_ITEMS} 条，
 * 避免灌爆上下文，金额数量 toPlainString 承载）；0 break 时明确回复账已对平。
 * 只读不改账，纠错走业务单据。
 */
public class RunConsistencyCheckTool implements Tool {

    public static final String NAME = "run_consistency_check";

    private final ConsistencyCheckService consistencyCheckService;

    public RunConsistencyCheckTool(ConsistencyCheckService consistencyCheckService) {
        this.consistencyCheckService = Objects.requireNonNull(consistencyCheckService,
                "consistencyCheckService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "跑数据一致性交叉校验，产出勾稽报告。用户问\"账对不对/有没有对不平/库存-成本-应收应付"
                + "有没有差错/帮我核一下账\"时调用。检查 7 条勾稽：库存流水与余额（数量/金额）恒等、"
                + "余额非负、应付=采购发票额、应收=销售发票额、销货成本 COGS=出库流水额、采购/销售三单"
                + "数量勾稽。返回总 break 数、按严重度(ERROR/WARN/INFO)分组计数与精简明细；0 break 即账已对平。"
                + "只读不改账，发现问题的纠错走业务单据红字冲销。";
    }

    @Override
    public String parameterSchema() {
        // 无入参：空对象
        return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        try {
            ConsistencyReport report = consistencyCheckService.check();
            return ToolResult.ok(toData(report));
        } catch (RuntimeException e) {
            return ToolResult.fail("一致性校验执行失败：" + e.getMessage());
        }
    }

    private static Map<String, Object> toData(ConsistencyReport report) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("checkedAt", report.checkedAt().toString());
        data.put("clean", report.clean());
        long total = report.breaks().size();
        data.put("breakCount", total);
        Map<String, Object> bySeverity = new LinkedHashMap<>();
        bySeverity.put("ERROR", report.errorCount());
        bySeverity.put("WARN", report.warnCount());
        bySeverity.put("INFO", report.infoCount());
        data.put("severityCounts", bySeverity);

        if (report.clean()) {
            data.put("summary", "账已对平（0 break）");
            return data;
        }
        data.put("summary", "发现 " + total + " 处对不上：ERROR " + report.errorCount()
                + " / WARN " + report.warnCount() + " / INFO " + report.infoCount()
                + "（仅列前 " + ArchiveToolSupport.MAX_ITEMS + " 条；全量见 GET /api/consistency/check）");
        List<Map<String, Object>> items = new ArrayList<>();
        int shown = 0;
        for (ConsistencyBreak b : report.breaks()) {
            if (shown++ >= ArchiveToolSupport.MAX_ITEMS) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("checkType", b.checkType().displayName());
            row.put("key", b.key());
            row.put("expected", b.expected());
            row.put("actual", b.actual());
            row.put("severity", b.severity().name());
            row.put("message", b.message());
            items.add(row);
        }
        data.put("breaks", items);
        if (total > ArchiveToolSupport.MAX_ITEMS) {
            data.put("truncated", total - ArchiveToolSupport.MAX_ITEMS);
        }
        return data;
    }
}
