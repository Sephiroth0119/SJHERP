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
import com.sjherp.domain.gl.PeriodClosedException;

/**
 * 冲销收款单工具（M4-T07c，HIGH——红字单、不可逆，框架强制确认卡片）：对已过账（COMPLETED）的
 * 收款单红冲——按收款单号反查已发生的应收核销记录逐条反向冲回（应收已核销额回退、状态回到部分核销/
 * 未核销），并红冲现金侧凭证（借贷对调），原单转「已冲销」（REVERSED）。
 *
 * <p>写操作经 {@link CollectionReceiptAppService#reverse}（CLAUDE.md 原则 1：唯一写入口，绝不绕过领域模型，
 * 反向核销经核销引擎、凭证红冲经凭证唯一写入口），全程同事务原子。已冲销/未过账单不可冲销；同一单据只能
 * 冲销一次（重复被拒）；现金侧凭证账期若已关账须先重开（高敏）。<b>冲收款单会解锁对应已核销销售发票的红冲</b>
 * （应收核销额回退至 0 后销售发票方可冲销）。权限点 finance:settlement。
 */
public class ReverseCollectionReceiptTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ReverseCollectionReceiptTool.class);

    public static final String NAME = "reverse_collection_receipt";

    private final CollectionReceiptAppService collectionReceiptAppService;

    public ReverseCollectionReceiptTool(CollectionReceiptAppService collectionReceiptAppService) {
        this.collectionReceiptAppService = Objects.requireNonNull(collectionReceiptAppService,
                "collectionReceiptAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "冲销收款单（红字单，不可逆，高风险）：对指定的已过账收款单 <doc_no> 做整单红冲——"
                + "按收款单号反查已发生的应收核销记录逐条反向冲回（应收已核销额回退、状态回到部分核销/未核销），"
                + "并红冲现金侧凭证（借贷对调），原收款单随之转为「已冲销」状态。"
                + "已冲销/未过账收款单不可冲销；同一单只能冲销一次（重复被拒）；现金侧凭证所在账期若已关账须先重开（高敏）。"
                + "冲收款单会解锁对应已核销销售发票的红冲。操作不可逆。"
                + "调用前先在回复正文复述要点（将对已过账收款单 <doc_no> 反向核销应收、红冲现金侧凭证、原单转已冲销、"
                + "不可逆）；系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"被冲销的收款单号（如 RCPT-202606-0001，须为已过账单据）"}},\
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
            CollectionReceipt receipt = collectionReceiptAppService.reverse(docNo, operator);
            log.info("Agent 冲销收款单（docNo={}, operator={}, sessionId={}）",
                    docNo, operator, context.sessionId());
            return ToolResult.ok(toData(receipt));
        } catch (CollectionReceiptNotFoundException e) {
            return ToolResult.fail("收款单不存在: " + docNo);
        } catch (PeriodClosedException e) {
            return ToolResult.fail("冲销被拒绝（现金侧凭证账期已关账，须先重开）: " + e.getMessage());
        } catch (IllegalStateTransitionException | IllegalStateException e) {
            return ToolResult.fail("冲销被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("冲销被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(CollectionReceipt receipt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", receipt.getDocNo());
        data.put("status", receipt.getStatus().name());
        data.put("totalAmount", receipt.totalAmount().toPlainString());
        data.put("note", "收款单已冲销：应收核销已反向冲回、现金侧凭证已红冲（不可逆，已解锁对应销售发票红冲）");
        return data;
    }
}
