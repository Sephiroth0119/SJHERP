package com.sjherp.app.tool.gl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.gl.GlDtos.ClosingPreviewLine;
import com.sjherp.app.gl.GlDtos.PeriodCloseReadiness;
import com.sjherp.app.gl.PeriodCloseService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.gl.AccountingPeriodNotFoundException;

/**
 * 月末关账预检工具（M4-T05，NORMAL，登录即可——只读，不写库不过账）：对指定账期跑关账可行性预检，
 * 返回「能不能关账（closeable）+ 阻断关账的一致性 ERROR 清单 + 不阻断的 WARN 清单 + 损益结转预览
 * + 本期收入/费用/净利润 + 当前试算平衡 Σ借/Σ贷」。供 Agent 在执行不可逆的关账前，先把"能不能关、
 * 关了什么样"展示给用户。
 *
 * <p>只读经 {@link PeriodCloseService#precheck}（{@code @Transactional(readOnly = true)}，
 * 不触发任何写/过账）。权限点 finance:period（与关账族操作统一口径，拆解 §2.3 裁定）。
 */
public class PrecheckPeriodCloseTool implements Tool {

    public static final String NAME = "precheck_period_close";

    private final PeriodCloseService periodCloseService;

    public PrecheckPeriodCloseTool(PeriodCloseService periodCloseService) {
        this.periodCloseService = Objects.requireNonNull(periodCloseService,
                "periodCloseService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "月末关账预检（只读，不会真的关账）：对指定账期 yyyyMM 跑关账可行性检查，返回是否可关账"
                + "（closeable）、阻断关账的数据一致性错误清单（ERROR，账实/勾稽不平时非空）、仅提示的"
                + "警告清单（WARN）、损益结转分录预览、本期收入/费用/净利润、当前试算平衡借贷合计。"
                + "用户想关账或问某账期能不能关、关了利润多少时，先调本工具展示给用户。"
                + "登录即可，无需确认。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "period":{"type":"string","pattern":"^[0-9]{6}$",\
                "description":"账期键 yyyyMM（如 202606）"}},\
                "required":["period"],"additionalProperties":false}""";
    }

    @Override
    public String requiredPermission() {
        return "finance:period";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String period = ArchiveToolSupport.str(arguments.get("period"));
        if (period == null) {
            return ToolResult.fail("账期 period 必填（yyyyMM，如 202606）");
        }
        try {
            PeriodCloseReadiness readiness = periodCloseService.precheck(period);
            return ToolResult.ok(toData(readiness));
        } catch (AccountingPeriodNotFoundException e) {
            return ToolResult.fail("账期不存在: " + period + "（需先开启该账期）");
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("关账预检被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(PeriodCloseReadiness readiness) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", readiness.period());
        data.put("status", readiness.status());
        data.put("closeable", readiness.closeable());
        data.put("alreadyClosed", readiness.alreadyClosed());
        data.put("consistencyErrors", readiness.consistencyErrors());
        data.put("consistencyWarnings", readiness.consistencyWarnings());
        data.put("totalRevenue", readiness.totalRevenue());
        data.put("totalExpense", readiness.totalExpense());
        data.put("netProfit", readiness.netProfit());
        data.put("trialBalanceDebit", readiness.trialBalanceDebit());
        data.put("trialBalanceCredit", readiness.trialBalanceCredit());
        List<Map<String, Object>> previewLines = new ArrayList<>();
        for (ClosingPreviewLine line : readiness.closingPreviewLines()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("accountCode", line.accountCode());
            row.put("accountName", line.accountName());
            row.put("debit", line.debit());
            row.put("credit", line.credit());
            previewLines.add(row);
        }
        data.put("closingPreviewLines", previewLines);
        data.put("note", readiness.closeable()
                ? "该账期可关账；如需执行不可逆关账请调 close_accounting_period（需用户确认）"
                : "该账期当前不可关账，请先处理上述阻断原因（ERROR 清单 / 账期非 OPEN / 已存在结转凭证）");
        return data;
    }
}
