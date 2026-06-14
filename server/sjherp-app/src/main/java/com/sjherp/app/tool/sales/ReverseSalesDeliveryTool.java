package com.sjherp.app.tool.sales;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.sales.SalesDeliveryAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesDeliveryNotFoundException;

/**
 * 冲销销售出库单工具（M4-T07b，HIGH——红字冲销不可逆，框架强制确认卡片）：将已过账销售出库单
 * （COMPLETED → REVERSED）红冲。同事务内库存按<b>原 COGS 反向入库</b>（账实归位）、回退销售订单累计
 * 发货量、红冲出库自动凭证（借贷对调，原凭证转已冲销）。
 *
 * <p>财务记录只可冲销不可物理删除（CLAUDE.md 原则 2）。关账期内不可冲销（凭证红冲账期 OPEN 校验拦截，
 * 闭月须先重开账期）。写操作经 {@link SalesDeliveryAppService#reverse}（CLAUDE.md 原则 1）；
 * 审计操作人记 agent:&lt;userId&gt;。权限点 sales:delivery（ADMIN/BOSS/WAREHOUSE）。
 */
public class ReverseSalesDeliveryTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ReverseSalesDeliveryTool.class);

    public static final String NAME = "reverse_sales_delivery";

    private final SalesDeliveryAppService salesDeliveryAppService;

    public ReverseSalesDeliveryTool(SalesDeliveryAppService salesDeliveryAppService) {
        this.salesDeliveryAppService = Objects.requireNonNull(salesDeliveryAppService,
                "salesDeliveryAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "冲销销售出库单（红字出库，COMPLETED → REVERSED）：将已过账出库单红冲，"
                + "库存按原销货成本（COGS）反向入库归位、回退销售订单发货量、红冲出库记账凭证（借贷对调）。"
                + "原单转为已冲销、不可再用（财务记录只可冲销不可删除）。关账期内不可冲销（须先重开账期）。"
                + "调用前先在回复正文复述要点（将冲销销售出库单 <doc_no>，库存反向归位、原单作废、不可逆）；"
                + "系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"销售出库单号（如 SD-202606-0001，须已过账）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "sales:delivery";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("销售出库单号 doc_no 必填");
        }
        String operator = ArchiveToolSupport.operator(context);
        try {
            SalesDelivery delivery = salesDeliveryAppService.reverse(docNo, operator);
            log.info("Agent 冲销销售出库单（docNo={}, operator={}, sessionId={}）",
                    docNo, operator, context.sessionId());
            return ToolResult.ok(toData(delivery));
        } catch (SalesDeliveryNotFoundException e) {
            return ToolResult.fail("销售出库单不存在: " + docNo);
        } catch (IllegalStateTransitionException | IllegalStateException e) {
            return ToolResult.fail("冲销销售出库单被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("冲销被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(SalesDelivery delivery) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", delivery.getDocNo());
        data.put("status", delivery.getStatus().name());
        data.put("salesOrderNo", delivery.getSalesOrderNo());
        data.put("reversedById", delivery.getReversedById());
        data.put("note", "销售出库单已冲销，库存已按原成本反向入库归位，出库凭证已红冲，销售订单发货量已回退");
        return data;
    }
}
