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
import com.sjherp.domain.inventory.InsufficientStockException;
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesDeliveryNotFoundException;

/**
 * 过账销售出库单工具（M3-T11，HIGH——真正扣减库存计算成本，不可撤销，框架强制确认卡片）：
 * 将已审核销售出库单（APPROVED → COMPLETED）过账，产生 SALES_OUT 流水，按移动加权成本
 * 算出 COGS 回填，回写销售订单累计发货量。库存不足则整批拒绝回滚。
 *
 * <p>过账不可撤销（退货走红字冲销 M4）。
 * 写操作经 {@link SalesDeliveryAppService#post}（CLAUDE.md 原则 1）；
 * 审计操作人记 agent:&lt;userId&gt;。权限点 sales:delivery（ADMIN/BOSS/WAREHOUSE）。
 */
public class PostSalesDeliveryTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(PostSalesDeliveryTool.class);

    public static final String NAME = "post_sales_delivery";

    private final SalesDeliveryAppService salesDeliveryAppService;

    public PostSalesDeliveryTool(SalesDeliveryAppService salesDeliveryAppService) {
        this.salesDeliveryAppService = Objects.requireNonNull(salesDeliveryAppService,
                "salesDeliveryAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "过账销售出库单（APPROVED → COMPLETED）：将已审核出库单过账，真正扣减库存、"
                + "按移动加权成本计算出库成本（COGS），回写销售订单发货量。"
                + "库存不足时整批拒绝（不部分出库）。过账后不可撤销（退货走红字冲销）。"
                + "调用前先在回复正文复述要点（将过账销售出库单 <doc_no>，过账后真正扣库存、不可撤销）；"
                + "系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"销售出库单号（如 SD-202606-0001）"}},\
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
            SalesDelivery delivery = salesDeliveryAppService.post(docNo, operator);
            log.info("Agent 过账销售出库单（docNo={}, operator={}, sessionId={}）",
                    docNo, operator, context.sessionId());
            return ToolResult.ok(toData(delivery));
        } catch (SalesDeliveryNotFoundException e) {
            return ToolResult.fail("销售出库单不存在: " + docNo);
        } catch (InsufficientStockException e) {
            return ToolResult.fail("库存不足，出库整批拒绝: " + e.getMessage());
        } catch (IllegalStateTransitionException | IllegalStateException e) {
            return ToolResult.fail("过账销售出库单被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("过账被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(SalesDelivery delivery) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", delivery.getDocNo());
        data.put("status", delivery.getStatus().name());
        data.put("salesOrderNo", delivery.getSalesOrderNo());
        data.put("totalCogs", delivery.totalCogs().toPlainString());
        data.put("note", "销售出库单已过账，库存已扣减，COGS 已计算，销售订单发货量已回写");
        return data;
    }
}
