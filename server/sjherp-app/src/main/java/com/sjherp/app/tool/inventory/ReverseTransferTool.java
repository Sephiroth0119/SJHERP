package com.sjherp.app.tool.inventory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.app.transfer.TransferAppService;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.transfer.TransferDocument;
import com.sjherp.domain.transfer.TransferNotFoundException;

/**
 * 冲销调拨单工具（M4-T07c，HIGH——红字单、不可逆，框架强制确认卡片）：对已过账（COMPLETED）的
 * 调拨单红冲——按已固化的原两腿成本对称反向库存（调出仓按原调出成本回补、调入仓按原调入成本出库），
 * 原单转「已冲销」（REVERSED）。<b>调拨不出 GL 凭证</b>（企业内部库存转移），故只反向库存、不红冲凭证。
 *
 * <p>写操作经 {@link TransferAppService#reverse}（CLAUDE.md 原则 1：唯一写入口，绝不绕过领域模型，
 * 库存两腿反向经库存唯一写入口），全程同事务原子。已冲销/未过账单不可冲销；同一单据只能冲销一次
 * （重复被拒）。权限点 inventory:transfer。
 */
public class ReverseTransferTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ReverseTransferTool.class);

    public static final String NAME = "reverse_transfer";

    private final TransferAppService transferAppService;

    public ReverseTransferTool(TransferAppService transferAppService) {
        this.transferAppService = Objects.requireNonNull(transferAppService,
                "transferAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "冲销调拨单（红字单，不可逆，高风险）：对指定的已过账调拨单 <doc_no> 做整单红冲——"
                + "按原两腿成本对称反向库存（调出仓按原调出成本回补、调入仓按原调入成本出库），企业库存价值守恒，"
                + "原调拨单随之转为「已冲销」状态。调拨不产生会计凭证（企业内部库存转移），故只反向库存。"
                + "已冲销/未过账调拨单不可冲销；同一单只能冲销一次（重复被拒）。操作不可逆。"
                + "调用前先在回复正文复述要点（将对已过账调拨单 <doc_no> 反向两腿库存、原单转已冲销、不可逆）；"
                + "系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"被冲销的调拨单号（如 TR-202606-0001，须为已过账单据）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "inventory:transfer";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("调拨单号 doc_no 必填");
        }
        String operator = ArchiveToolSupport.operator(context);
        try {
            TransferDocument document = transferAppService.reverse(docNo, operator);
            log.info("Agent 冲销调拨单（docNo={}, operator={}, sessionId={}）",
                    docNo, operator, context.sessionId());
            return ToolResult.ok(toData(document));
        } catch (TransferNotFoundException e) {
            return ToolResult.fail("调拨单不存在: " + docNo);
        } catch (IllegalStateTransitionException | IllegalStateException e) {
            return ToolResult.fail("冲销被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("冲销被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(TransferDocument document) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", document.getDocNo());
        data.put("status", document.getStatus().name());
        data.put("note", "调拨单已冲销：两腿库存已按原成本对称反向（调出仓回补、调入仓出库），企业库存价值守恒（不可逆）");
        return data;
    }
}
