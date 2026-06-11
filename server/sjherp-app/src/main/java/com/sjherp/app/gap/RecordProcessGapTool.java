package com.sjherp.app.gap;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.domain.gap.BusinessModule;
import com.sjherp.domain.gap.GapRecord;
import com.sjherp.domain.gap.GapRecordCommand;
import com.sjherp.domain.gap.GapRecordService;
import com.sjherp.domain.gap.GapSeverity;

/**
 * 流程缺口记录工具（M1-T04，常驻注册，非 dev-only）。
 *
 * <p>Agent 判断"用户需求当前能力做不到"且用户同意记录后调用本工具，
 * 把缺口结构化落库（经 {@link GapRecordService} 唯一写入口）。记录缺口
 * 本身不产生业务影响，风险级别为 NORMAL（不走框架高风险拦截；
 * 是否记录由系统提示词引导的普通选项确认）。
 *
 * <p>成功返回缺口编号（GAP-年月-序号）+ 给用户的引导文案，
 * 模型据此告知用户「已记录，开发团队会评估，解决后会通知你」。
 */
public class RecordProcessGapTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(RecordProcessGapTool.class);

    public static final String NAME = "record_process_gap";

    private final GapRecordService gapRecordService;

    public RecordProcessGapTool(GapRecordService gapRecordService) {
        this.gapRecordService = Objects.requireNonNull(gapRecordService, "gapRecordService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "记录流程缺口：当用户提出的需求超出系统当前能力（没有对应工具能完成）、"
                + "且用户确认希望记录该需求时调用。把用户场景、期望效果、缺失能力结构化提交给开发团队评估。"
                + "调用前必须先向用户复述要点并获得用户同意。返回缺口编号（如 GAP-202606-0001）。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "title":{"type":"string","description":"缺口一句话标题（中文，200 字以内）"},\
                "scenario":{"type":"string","description":"用户场景：用户想在什么业务情境下做什么（原文或复述，中文）"},\
                "expected_behavior":{"type":"string","description":"用户期望系统做到什么效果（中文）"},\
                "missing_capability":{"type":"string","description":"你判断系统当前缺失的能力（中文）"},\
                "business_module":{"type":"string","enum":["PURCHASE","SALES","INVENTORY","PRODUCTION","FINANCE","GENERAL"],\
                "description":"所属业务模块：PURCHASE 采购 / SALES 销售 / INVENTORY 库存 / PRODUCTION 生产 / FINANCE 财务 / GENERAL 通用或无法归类"},\
                "severity":{"type":"string","enum":["LOW","MEDIUM","HIGH"],\
                "description":"严重度：LOW 锦上添花 / MEDIUM 影响效率但业务能跑 / HIGH 业务被卡住无替代方案"}},\
                "required":["title","scenario","expected_behavior","missing_capability","business_module","severity"],\
                "additionalProperties":false}""";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        // 参数已由框架按 parameterSchema 校验（required/type/enum），这里直接取值
        GapRecordCommand command = new GapRecordCommand(
                context.sessionId(),
                str(arguments.get("title")),
                str(arguments.get("scenario")),
                str(arguments.get("expected_behavior")),
                str(arguments.get("missing_capability")),
                BusinessModule.valueOf(str(arguments.get("business_module")).toUpperCase(Locale.ROOT)),
                GapSeverity.valueOf(str(arguments.get("severity")).toUpperCase(Locale.ROOT)),
                reporter(context));
        try {
            // 操作人记 Agent 标识（审计要求：区分人工与 Agent 操作），最终责任人在 reporter
            GapRecord record = gapRecordService.create(command, "agent:" + reporter(context));
            log.info("流程缺口已落库（gapNo={}, sessionId={}, module={}, severity={}）",
                    record.getGapNo(), record.getSessionId(),
                    record.getBusinessModule(), record.getSeverity());
            return ToolResult.ok(Map.of(
                    "gapNo", record.getGapNo(),
                    "status", record.getStatus().name(),
                    "guidance", "缺口已记录，编号 " + record.getGapNo()
                            + "。请告知用户：已记录这个需求，开发团队会评估，解决后会通知你。"));
        } catch (IllegalArgumentException e) {
            // 领域校验拒绝（字段超长等）→ 错误回灌，模型修正参数重试或向用户说明
            return ToolResult.fail("缺口记录被拒绝: " + e.getMessage());
        }
    }

    /** 提出人：会话所属用户（当前为占位 userId，M2-T05 登录落地后为真实用户） */
    private static String reporter(ToolContext context) {
        return (context.userId() == null || context.userId().isBlank())
                ? "anonymous" : context.userId();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
