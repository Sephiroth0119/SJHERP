package com.sjherp.app.tool.purchase;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.purchase.PurchaseReceiptAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.purchase.PurchaseReceiptNotFoundException;

/**
 * 过账采购入库单工具（M3-T11，HIGH——真正入库，不可撤销，框架强制确认卡片）。
 *
 * <p>将已审核（APPROVED）的采购入库单过账为已完成（COMPLETED），产生 PURCHASE_IN 入库流水、
 * 更新库存余额（移动加权平均成本）、回写采购订单到货量。过账后不可撤销。
 *
 * <p>写操作经 {@link PurchaseReceiptAppService#post}；审计操作人记 agent:&lt;userId&gt;。
 * 权限点 purchase:receipt（ADMIN/BOSS/WAREHOUSE）。
 */
public class PostPurchaseReceiptTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(PostPurchaseReceiptTool.class);

    public static final String NAME = "post_purchase_receipt";

    private final PurchaseReceiptAppService purchaseReceiptAppService;

    public PostPurchaseReceiptTool(PurchaseReceiptAppService purchaseReceiptAppService) {
        this.purchaseReceiptAppService = Objects.requireNonNull(purchaseReceiptAppService,
                "purchaseReceiptAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "过账采购入库单（APPROVED → COMPLETED）：真正入库——产生库存入库流水（PURCHASE_IN）、"
                + "更新库存余额与加权平均成本、回写采购订单到货量。过账后不可撤销。"
                + "调用前先在回复正文复述要点（过账后将真正入库，不可撤销）；"
                + "系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return "{\"type\":\"object\",\"properties\":{\"doc_no\":{\"type\":\"string\",\"description\":\"采购入库单号（如 PR-202606-0001）\"}},\"required\":[\"doc_no\"],\"additionalProperties\":false}";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "purchase:receipt";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("采购入库单号 doc_no 必填");
        }
        String operator = ArchiveToolSupport.operator(context);
        try {
            PurchaseReceipt receipt = purchaseReceiptAppService.post(docNo, operator);
            log.info("Agent 过账采购入库单（docNo={}, operator={}, sessionId={}）",
                    docNo, operator, context.sessionId());
            return ToolResult.ok(toData(receipt));
        } catch (PurchaseReceiptNotFoundException e) {
            return ToolResult.fail("采购入库单不存在: " + docNo);
        } catch (IllegalStateTransitionException e) {
            return ToolResult.fail("采购入库单状态流转被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("过账被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(PurchaseReceipt receipt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", receipt.getDocNo());
        data.put("status", receipt.getStatus().name());
        data.put("totalAmount", receipt.totalAmount().toPlainString());
        data.put("note", "采购入库单已过账，库存已更新、采购订单到货量已回写");
        return data;
    }
}
