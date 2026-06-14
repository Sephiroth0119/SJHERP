package com.sjherp.app.tool.gl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.gl.VoucherAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.gl.AccountBalance;

/**
 * 单科目余额查询工具（M4-T08，NORMAL，只读）：按科目编码+账期查询该科目在指定账期内的借贷发生额与净额，
 * 返回单行结果。适合用户问"1122 本月发生额"等精确科目查询场景（试算平衡则用 query_trial_balance）。
 *
 * <p>只读经 {@link VoucherAppService#accountBalance}（{@code @Transactional(readOnly = true)}）。
 * 权限点 finance:voucher（与 GlVoucherController /account-balance 端点同口径）。
 * 金额一律 {@link java.math.BigDecimal#toPlainString}。
 */
public class QueryAccountBalanceTool implements Tool {

    public static final String NAME = "query_account_balance";

    private final VoucherAppService voucherAppService;

    public QueryAccountBalanceTool(VoucherAppService voucherAppService) {
        this.voucherAppService = Objects.requireNonNull(voucherAppService, "voucherAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询单个科目在指定账期的借贷发生额：传入科目编码（accountCode）和账期（period yyyyMM），"
                + "返回该科目在该账期已过账凭证的借方合计、贷方合计与净额（借−贷）。"
                + "用户问\"1122 本月有多少发生额\"\"应付账款本期贷方\"\"指定科目余额\"时调用。"
                + "accountCode/period 均必填；period 格式 yyyyMM（如 202606）。"
                + "需要 finance:voucher 权限。金额返回字符串格式（非 JSON 数字）。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "accountCode":{"type":"string",\
                "description":"科目编码（如 1122、220202）"},\
                "period":{"type":"string","pattern":"^[0-9]{6}$",\
                "description":"账期键 yyyyMM（如 202606）"}},\
                "required":["accountCode","period"],"additionalProperties":false}""";
    }

    @Override
    public String requiredPermission() {
        return "finance:voucher";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String accountCode = ArchiveToolSupport.str(arguments.get("accountCode"));
        if (accountCode == null || accountCode.isBlank()) {
            return ToolResult.fail("accountCode 必填（如 1122、220202）");
        }
        String period = ArchiveToolSupport.str(arguments.get("period"));
        if (period == null || period.isBlank()) {
            return ToolResult.fail("period 必填（格式 yyyyMM，如 202606）");
        }
        if (!period.matches("^[0-9]{6}$")) {
            return ToolResult.fail("period 格式错误，须为 6 位数字 yyyyMM，如 202606");
        }

        try {
            AccountBalance ab = voucherAppService.accountBalance(accountCode, period);
            return ToolResult.ok(toData(ab));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("查询科目余额失败：" + e.getMessage());
        }
    }

    private static Map<String, Object> toData(AccountBalance ab) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountCode", ab.accountCode());
        data.put("totalDebit", ab.totalDebit().toPlainString());
        data.put("totalCredit", ab.totalCredit().toPlainString());
        data.put("netBalance", ab.netBalance().toPlainString());
        return data;
    }
}
