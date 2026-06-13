package com.sjherp.app.tool.collection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.collection.CollectionReceiptAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.collection.CollectionReceipt;
import com.sjherp.domain.collection.CollectionReceiptNotFoundException;
import com.sjherp.domain.common.IllegalStateTransitionException;

/**
 * 审核收款单工具（M4-T04c，HIGH——状态流转，框架强制确认卡片）：将收款单从草稿（DRAFT）
 * 推进至已审核（APPROVED），审核后业务内容锁定、方可过账。审核不冲减应收、不生成凭证，过账才落账。
 *
 * <p>写操作经 {@link CollectionReceiptAppService#approve}（CLAUDE.md 原则 1）；
 * 审计操作人记 agent:&lt;userId&gt;。权限点 finance:settlement（复用核销写权限）。
 */
public class ApproveCollectionReceiptTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ApproveCollectionReceiptTool.class);

    public static final String NAME = "approve_collection_receipt";

    private final CollectionReceiptAppService collectionReceiptAppService;

    public ApproveCollectionReceiptTool(CollectionReceiptAppService collectionReceiptAppService) {
        this.collectionReceiptAppService = Objects.requireNonNull(collectionReceiptAppService,
                "collectionReceiptAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "审核收款单（DRAFT → APPROVED）：确认收款单数据无误、锁定业务内容、允许后续过账。"
                + "审核不冲减应收、不生成凭证，过账才真正落账。"
                + "调用前先在回复正文复述要点（将审核收款单 <doc_no>）；"
                + "系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"收款单号（如 RCPT-202606-0001）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "finance:settlement";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("收款单号 doc_no 必填");
        }
        String operator = ArchiveToolSupport.operator(context);
        try {
            CollectionReceipt receipt = collectionReceiptAppService.approve(docNo, operator);
            log.info("Agent 审核收款单（docNo={}, operator={}, sessionId={}）",
                    docNo, operator, context.sessionId());
            return ToolResult.ok(toData(receipt));
        } catch (CollectionReceiptNotFoundException e) {
            return ToolResult.fail("收款单不存在: " + docNo);
        } catch (IllegalStateTransitionException | IllegalStateException e) {
            return ToolResult.fail("审核收款单被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("审核被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(CollectionReceipt receipt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", receipt.getDocNo());
        data.put("status", receipt.getStatus().name());
        data.put("customerId", receipt.getCustomerId());
        data.put("totalAmount", receipt.totalAmount().toPlainString());
        data.put("note", "收款单已审核，可进行过账（过账后冲减应收、生成现金侧凭证，不可撤销）");
        return data;
    }
}
