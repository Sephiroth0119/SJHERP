package com.sjherp.app.chat;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.sjherp.agent.reply.Action;
import com.sjherp.agent.reply.AgentReply;
import com.sjherp.agent.reply.Form;
import com.sjherp.agent.reply.Option;

/**
 * 规则占位 Agent（演示用）。
 *
 * <p>【待替换】这是 LLM 接入前的临时实现：待 sjherp-agent 的 {@code LlmClient}
 * 有真实厂商实现（DeepSeek/通义/Claude/GPT，infra 层提供）并接入工具注册后，
 * 本类整体替换为真正的 Agent 编排循环。占位期间只用于：
 * <ul>
 *   <li>打通前后端的选项返回协议 v0.1（选项卡片 / 表单 / Human-in-the-loop 确认）；</li>
 *   <li>验证会话持久化与回放（ADR-001）。</li>
 * </ul>
 *
 * <p>规则：识别「采购 / 销售 / 库存」关键词返回演示回复，其中采购场景演示
 * requiresConfirmation=true + risk=high 的高风险确认流程；其余输入 echo。
 */
@Component
public class PlaceholderAgent {

    /** 处理用户自由文本 */
    public AgentReply replyToText(String text) {
        if (text.contains("采购")) {
            return purchaseConfirmationDemo();
        }
        if (text.contains("销售")) {
            return salesOptionsDemo();
        }
        if (text.contains("库存")) {
            return inventoryOptionsDemo();
        }
        return AgentReply.text("（占位 Agent）已收到：" + text
                + "\n\n当前为演示模式，试试输入「采购」「销售」或「库存」体验选项卡片。");
    }

    /** 处理用户点击的选项（已由 ChatService 凭最近一条回复按 id 还原，防伪造） */
    public AgentReply replyToOption(Option option) {
        return switch (option.id()) {
            // —— 采购确认场景（Human-in-the-loop：点击确认后才"执行"） ——
            case "opt-sup-a" -> AgentReply.text(
                    "已按 **供应商A（华东金属）** 创建采购订单**草稿** PO-DEMO-001（M-0042 不锈钢板 2mm × 500kg，"
                            + "单价 ¥18.50/kg）。\n\n演示数据：草稿仍需走单据状态机审核后才会过账。");
            case "opt-sup-b" -> AgentReply.text(
                    "已按 **供应商B（南方钢业）** 创建采购订单**草稿** PO-DEMO-002（M-0042 不锈钢板 2mm × 500kg，"
                            + "单价 ¥17.90/kg）。\n\n演示数据：草稿仍需走单据状态机审核后才会过账。");
            case "opt-po-cancel" -> AgentReply.text("好的，已取消本次采购，不会创建任何单据。");

            // —— 销售场景 ——
            case "opt-sale-create" -> salesOrderFormDemo();
            case "opt-sale-query" -> AgentReply.text(
                    "近期销售订单（演示数据）：\n\n"
                            + "- SO-DEMO-001 客户「星辰机械」 ¥45,000 已审核\n"
                            + "- SO-DEMO-002 客户「恒达精工」 ¥12,800 草稿");

            // —— 库存场景 ——
            case "opt-inv-query" -> AgentReply.text(
                    "库存台账摘要（演示数据）：\n\n"
                            + "| 物料 | 仓库 | 结存数量 |\n|---|---|---|\n"
                            + "| M-0042 不锈钢板 2mm | 原料一仓 | 120 kg |\n"
                            + "| P-1001 成品支架 | 成品仓 | 860 件 |");
            case "opt-inv-count" -> AgentReply.text(
                    "已记录盘点意向（演示数据）。正式版本中这里会创建盘点单草稿并走审核流程。");

            // 其余选项：语义化回答，继续对话
            default -> AgentReply.text("（占位 Agent）已收到你的选择：" + option.label());
        };
    }

    /** 处理用户提交的表单（values 一律字符串，金额/数量由后端 BigDecimal 解析） */
    public AgentReply replyToForm(String formId, Map<String, String> values) {
        if ("form-so-demo".equals(formId)) {
            return AgentReply.text("已创建销售订单**草稿** SO-DEMO-003（演示数据）：\n\n"
                    + "- 客户：" + values.getOrDefault("customer", "（未填写）") + "\n"
                    + "- 数量：" + values.getOrDefault("qty", "（未填写)") + "\n"
                    + "- 交货日期：" + values.getOrDefault("deliveryDate", "（未填写）") + "\n"
                    + "- 发货仓库：" + values.getOrDefault("warehouseId", "（未填写）")
                    + "\n\n草稿仍需走单据状态机审核。");
        }
        return AgentReply.text("（占位 Agent）已收到表单 " + formId + " 的提交：" + values);
    }

    /** 采购场景：高风险确认演示（requiresConfirmation=true + risk=high + 取消项） */
    private AgentReply purchaseConfirmationDemo() {
        return AgentReply.confirmation(
                "物料 **M-0042 不锈钢板 2mm** 需补货 500kg，找到 2 家合格供应商。"
                        + "下采购订单属于高风险操作，请人工确认选择：",
                List.of(
                        Option.highRisk("opt-sup-a", "供应商A（华东金属）",
                                "单价 ¥18.50/kg，交期 3 天，近 90 天准交率 98%",
                                new Action("CREATE_PURCHASE_ORDER", Map.of(
                                        "supplierId", "SUP-001", "materialId", "M-0042", "qty", "500"))),
                        Option.highRisk("opt-sup-b", "供应商B（南方钢业）",
                                "单价 ¥17.90/kg，交期 7 天，近 90 天准交率 91%",
                                new Action("CREATE_PURCHASE_ORDER", Map.of(
                                        "supplierId", "SUP-002", "materialId", "M-0042", "qty", "500"))),
                        Option.of("opt-po-cancel", "暂不下单")));
    }

    /** 销售场景：普通选项演示 */
    private AgentReply salesOptionsDemo() {
        return AgentReply.withOptions("销售方面我可以帮你：",
                List.of(
                        Option.of("opt-sale-create", "创建销售订单", "通过表单补充客户与数量等信息", null),
                        Option.of("opt-sale-query", "查询近期销售订单", "查看最近的销售订单列表（演示数据）", null)));
    }

    /** 库存场景：普通选项演示 */
    private AgentReply inventoryOptionsDemo() {
        return AgentReply.withOptions("库存方面我可以帮你：",
                List.of(
                        Option.of("opt-inv-query", "查询库存台账", "按物料/仓库查看结存（演示数据）", null),
                        Option.of("opt-inv-count", "发起盘点", "创建盘点单草稿（演示）", null)));
    }

    /** 销售订单表单演示（金额/数量字段为 decimal，字符串传输） */
    private AgentReply salesOrderFormDemo() {
        return AgentReply.withForm("好的，创建销售订单前请补充以下信息：",
                new Form("form-so-demo", "销售订单（演示）",
                        List.of(
                                new Form.FormField("customer", "客户名称", Form.FieldType.TEXT,
                                        true, "如：星辰机械", null, null),
                                new Form.FormField("qty", "数量(件)", Form.FieldType.DECIMAL,
                                        true, null, "100", null),
                                new Form.FormField("deliveryDate", "交货日期", Form.FieldType.DATE,
                                        true, null, null, null),
                                new Form.FormField("warehouseId", "发货仓库", Form.FieldType.SELECT,
                                        true, null, null, List.of(
                                                new Form.SelectOption("WH-01", "成品一仓"),
                                                new Form.SelectOption("WH-02", "成品二仓")))),
                        "创建草稿",
                        new Action("PREPARE_SALES_ORDER", Map.of())));
    }
}
