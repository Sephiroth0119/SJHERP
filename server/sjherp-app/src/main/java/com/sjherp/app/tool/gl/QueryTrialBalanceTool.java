package com.sjherp.app.tool.gl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.gl.VoucherAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.gl.AccountBalance;

/**
 * 试算平衡查询工具（M4-T08，NORMAL，只读）：对指定账期已过账（APPROVED）凭证行按科目汇总借贷发生额，
 * 返回按科目编码升序排列的试算平衡表，以及总借方合计与总贷方合计（Σ借==Σ贷校验）。
 *
 * <p>只读经 {@link VoucherAppService#trialBalance}（{@code @Transactional(readOnly = true)}）。
 * 权限点 finance:voucher（与 GlVoucherController /trial-balance 端点同口径）。
 * 金额一律 {@link java.math.BigDecimal#toPlainString}，日期 ISO 字符串。
 */
public class QueryTrialBalanceTool implements Tool {

    public static final String NAME = "query_trial_balance";

    private final VoucherAppService voucherAppService;

    public QueryTrialBalanceTool(VoucherAppService voucherAppService) {
        this.voucherAppService = Objects.requireNonNull(voucherAppService, "voucherAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询试算平衡表：对指定账期（yyyyMM）已过账凭证按科目汇总借贷发生额，"
                + "返回科目编码/借方合计/贷方合计/净额（借−贷），以及全表 Σ借/Σ贷（应相等）。"
                + "用户问\"试算平衡\"\"借贷是否平\"\"看看科目发生额\"时调用。"
                + "period 必填（格式 yyyyMM，如 202606）。"
                + "需要 finance:voucher 权限。金额返回字符串格式（非 JSON 数字）。";
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
        return "finance:voucher";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String period = ArchiveToolSupport.str(arguments.get("period"));
        if (period == null || period.isBlank()) {
            return ToolResult.fail("period 必填（格式 yyyyMM，如 202606）");
        }
        if (!period.matches("^[0-9]{6}$")) {
            return ToolResult.fail("period 格式错误，须为 6 位数字 yyyyMM，如 202606");
        }

        List<AccountBalance> balances = voucherAppService.trialBalance(period);
        return ToolResult.ok(toData(period, balances));
    }

    private static Map<String, Object> toData(String period, List<AccountBalance> balances) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", period);

        java.math.BigDecimal totalDebit = java.math.BigDecimal.ZERO;
        java.math.BigDecimal totalCredit = java.math.BigDecimal.ZERO;

        List<Map<String, Object>> lines = new ArrayList<>();
        for (AccountBalance ab : balances) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("accountCode", ab.accountCode());
            row.put("totalDebit", ab.totalDebit().toPlainString());
            row.put("totalCredit", ab.totalCredit().toPlainString());
            row.put("netBalance", ab.netBalance().toPlainString());
            lines.add(row);
            totalDebit = totalDebit.add(ab.totalDebit());
            totalCredit = totalCredit.add(ab.totalCredit());
        }
        data.put("lines", lines);
        data.put("totalDebit", totalDebit.toPlainString());
        data.put("totalCredit", totalCredit.toPlainString());
        data.put("balanced", totalDebit.compareTo(totalCredit) == 0);
        return data;
    }
}
