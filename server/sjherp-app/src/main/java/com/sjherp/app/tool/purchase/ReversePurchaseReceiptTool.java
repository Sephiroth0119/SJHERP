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
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.purchase.PurchaseReceiptNotFoundException;

/**
 * 冲销采购入库单工具（M4-T07b，HIGH——红字单、不可逆，框架强制确认卡片）：对已过账（COMPLETED）的
 * 采购入库单红冲——按已固化的原收货成本反向出库（冲减库存）、回退采购订单到货量、红冲入库自动凭证
 * （借贷对调），原单转「已冲销」（REVERSED）。
 *
 * <p>写操作经 {@link PurchaseReceiptAppService#reverse}（CLAUDE.md 原则 1：唯一写入口，绝不绕过领域模型，
 * 库存反向经库存唯一写入口、凭证红冲经凭证唯一写入口），全程同事务原子。已冲销/未过账单不可冲销；
 * 同一单据只能冲销一次（重复冲销被拒）；自动凭证账期若已关账须先重开（高敏）。权限点 purchase:receipt。
 */
public class ReversePurchaseReceiptTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ReversePurchaseReceiptTool.class);

    public static final String NAME = "reverse_purchase_receipt";

    private final PurchaseReceiptAppService purchaseReceiptAppService;

    public ReversePurchaseReceiptTool(PurchaseReceiptAppService purchaseReceiptAppService) {
        this.purchaseReceiptAppService = Objects.requireNonNull(purchaseReceiptAppService,
                "purchaseReceiptAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "冲销采购入库单（红字单，不可逆，高风险）：对指定的已过账采购入库单 <doc_no> 做整单红冲——"
                + "按原收货成本反向出库（冲减库存）、回退采购订单已到货量、红冲入库自动凭证（借贷对调），"
                + "原入库单随之转为「已冲销」状态。已冲销/未过账入库单不可冲销；同一单只能冲销一次（重复被拒）；"
                + "入库自动凭证所在账期若已关账须先重开（高敏）。操作不可逆。"
                + "调用前先在回复正文复述要点（将对已过账入库单 <doc_no> 反向库存、回退到货量、红冲凭证、原单转已冲销、"
                + "不可逆）；系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"被冲销的采购入库单号（如 PR-202606-0001，须为已过账单据）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
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
            PurchaseReceipt receipt = purchaseReceiptAppService.reverse(docNo, operator);
            log.info("Agent 冲销采购入库单（docNo={}, operator={}, sessionId={}）",
                    docNo, operator, context.sessionId());
            return ToolResult.ok(toData(receipt));
        } catch (PurchaseReceiptNotFoundException e) {
            return ToolResult.fail("采购入库单不存在: " + docNo);
        } catch (PeriodClosedException e) {
            return ToolResult.fail("冲销被拒绝（入库凭证账期已关账，须先重开）: " + e.getMessage());
        } catch (IllegalStateTransitionException | IllegalStateException e) {
            return ToolResult.fail("冲销被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("冲销被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(PurchaseReceipt receipt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", receipt.getDocNo());
        data.put("status", receipt.getStatus().name());
        data.put("totalAmount", receipt.totalAmount().toPlainString());
        data.put("note", "采购入库单已冲销：库存按原成本反向出库、采购订单到货量已回退、入库凭证已红冲（不可逆）");
        return data;
    }
}
