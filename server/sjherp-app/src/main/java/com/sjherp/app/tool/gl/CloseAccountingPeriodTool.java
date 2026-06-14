package com.sjherp.app.tool.gl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.gl.GlDtos.PeriodCloseResult;
import com.sjherp.app.gl.PeriodCloseBlockedException;
import com.sjherp.app.gl.PeriodCloseService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.gl.AccountingPeriodNotFoundException;
import com.sjherp.domain.gl.PeriodClosedException;

/**
 * 月末结转关账工具（M4-T05，HIGH——全系统最高风险路径之一，不可逆，框架强制确认卡片）：对指定账期
 * 编排「结转前一致性闸门 → 损益结转凭证（损益类归集入本年利润 4103）→ 试算平衡断言 → 关账」四步，
 * 单一事务原子（任一步失败整事务回滚，绝不留半结转/半关账）。关账后该账期禁止再过账。
 *
 * <p>写操作经 {@link PeriodCloseService#close}（CLAUDE.md 原则 1：唯一编排入口，绝不绕过领域模型）；
 * 审计操作人记 agent:&lt;userId&gt;（关账与结转凭证过账均 @Audited）。权限点 finance:period
 * （与账期开启/关账族统一口径）。
 */
public class CloseAccountingPeriodTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CloseAccountingPeriodTool.class);

    public static final String NAME = "close_accounting_period";

    private final PeriodCloseService periodCloseService;

    public CloseAccountingPeriodTool(PeriodCloseService periodCloseService) {
        this.periodCloseService = Objects.requireNonNull(periodCloseService,
                "periodCloseService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "月末结转关账（不可逆，高风险）：对指定账期 yyyyMM 依次执行——① 跑结转前数据一致性检查"
                + "（账实/勾稽存在 ERROR 则拒绝关账并返回原因清单）；② 生成并过账结转损益凭证，把本期"
                + "损益类科目（收入/费用）净额全部结转入「4103 本年利润」；③ 试算平衡断言（结转后损益归零、"
                + "Σ借=Σ贷）；④ 关账（OPEN→CLOSED）。全程单一事务，任一步失败整体回滚。"
                + "关账后该账期禁止再过账，且不可逆（重开后重结须先冲销原结转凭证，M4-T07）。"
                + "调用前先在回复正文复述要点（将对账期 <period> 跑一致性检查→生成并过账结转损益凭证"
                + "→损益结转入本年利润 4103→关账，关账后该期禁止再过账、不可逆）；"
                + "系统会自动请求用户确认后才执行。";
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
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
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
        String operator = ArchiveToolSupport.operator(context);
        try {
            PeriodCloseResult result = periodCloseService.close(period, operator);
            log.info("Agent 月末结转关账（period={}, closingVoucherDocNo={}, operator={}, sessionId={}）",
                    period, result.closingVoucherDocNo(), operator, context.sessionId());
            return ToolResult.ok(toData(result));
        } catch (AccountingPeriodNotFoundException e) {
            return ToolResult.fail("账期不存在: " + period + "（需先开启该账期）");
        } catch (PeriodCloseBlockedException e) {
            // 闸门拒绝（账期非 OPEN / 既存结转凭证 / 一致性 ERROR）：携 reasons 供 Agent 复述
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("period", period);
            data.put("reasons", e.getReasons());
            return new ToolResult(false, data, "关账被拒绝: " + e.getMessage());
        } catch (PeriodClosedException e) {
            return ToolResult.fail("关账被拒绝（账期已关闭）: " + e.getMessage());
        } catch (IllegalStateException e) {
            // 试算平衡断言兜底等状态约束类拒绝
            return ToolResult.fail("关账被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("关账被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(PeriodCloseResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", result.period());
        data.put("closingVoucherDocNo", result.closingVoucherDocNo());
        data.put("totalRevenue", result.totalRevenue());
        data.put("totalExpense", result.totalExpense());
        data.put("netProfit", result.netProfit());
        data.put("trialBalanceDebit", result.trialBalanceDebit());
        data.put("trialBalanceCredit", result.trialBalanceCredit());
        data.put("closedBy", result.closedBy());
        data.put("closedAt", result.closedAt());
        data.put("note", "账期已关账，损益已结转入本年利润 4103，该账期禁止再过账（不可逆）");
        return data;
    }
}
