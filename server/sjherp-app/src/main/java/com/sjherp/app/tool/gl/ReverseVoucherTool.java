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
import com.sjherp.app.gl.VoucherAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.gl.Voucher;
import com.sjherp.domain.gl.VoucherNotFoundException;

/**
 * 冲销凭证工具（M4-T07a，HIGH——对已过账凭证生成红字凭证、原凭证转已冲销，不可逆，框架强制确认卡片）：
 * 对已过账（APPROVED）凭证生成借贷对调（反向分录）的红字凭证并在原账期过账，原凭证转 REVERSED、
 * 双向 linkage 落库。
 *
 * <p>写操作经 {@link VoucherAppService#reverse}（CLAUDE.md 原则 1：唯一写入口，绝不绕过领域模型）；
 * 审计操作人记 agent:&lt;userId&gt;（voucher.reverse 全程 @Audited）。权限点 finance:voucher
 * （与凭证建单/过账族统一口径）。
 */
public class ReverseVoucherTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ReverseVoucherTool.class);

    public static final String NAME = "reverse_voucher";

    private final VoucherAppService voucherAppService;

    public ReverseVoucherTool(VoucherAppService voucherAppService) {
        this.voucherAppService = Objects.requireNonNull(voucherAppService,
                "voucherAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "冲销凭证（红字凭证，不可逆，高风险）：对指定的已过账凭证 <doc_no> 生成一张借贷对调"
                + "（反向分录、不用负额）的红字凭证，在原凭证账期（须未关账）自动过账以抵消原凭证；"
                + "原凭证随之转为「已冲销」状态，红字凭证与原凭证双向关联。"
                + "已冲销/草稿/作废凭证不可冲销；同一凭证只能冲销一次（重复冲销被拒）；"
                + "原凭证账期若已关账须先重开（高敏）。操作不可逆。"
                + "调用前先在回复正文复述要点（将对已过账凭证 <doc_no> 生成借贷对调红字凭证、原凭证转已冲销、"
                + "不可逆）；系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"被冲销的原凭证号（如 VCH-202606-0001，须为已过账凭证）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "finance:voucher";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("凭证号 doc_no 必填");
        }
        String operator = ArchiveToolSupport.operator(context);
        try {
            Voucher red = voucherAppService.reverse(docNo, operator);
            log.info("Agent 冲销凭证（原 docNo={}, 红字 docNo={}, operator={}, sessionId={}）",
                    docNo, red.getDocNo(), operator, context.sessionId());
            return ToolResult.ok(toData(docNo, red));
        } catch (VoucherNotFoundException e) {
            return ToolResult.fail("凭证不存在: " + docNo);
        } catch (PeriodClosedException e) {
            return ToolResult.fail("冲销被拒绝（账期已关账，须先重开）: " + e.getMessage());
        } catch (IllegalStateTransitionException | IllegalStateException e) {
            // 非 APPROVED / 已冲销 / 既存红字等状态约束类拒绝
            return ToolResult.fail("冲销被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("冲销被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(String originalDocNo, Voucher red) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("originalDocNo", originalDocNo);
        data.put("reversalDocNo", red.getDocNo());
        data.put("period", red.getPeriod());
        data.put("status", red.getStatus().name());
        data.put("totalAmount", red.getTotalAmount().toPlainString());
        data.put("reversalOfId", red.getReversalOfId());
        data.put("note", "已生成借贷对调红字凭证并过账，原凭证已转为已冲销（不可逆）");
        return data;
    }
}
