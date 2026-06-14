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
import com.sjherp.app.stocktake.StocktakeService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.stocktake.StockCountDocument;
import com.sjherp.domain.stocktake.StockCountNotFoundException;

/**
 * 冲销盘点单工具（M4-T07c，HIGH——红字单、不可逆，框架强制确认卡片）：对已过账（COMPLETED）的
 * 盘点单红冲——按已固化的原盘盈/盘亏成本对称反向库存（原盘盈→反向出库、原盘亏→反向入库），
 * 原单转「已冲销」（REVERSED）。<b>盘点不出 GL 凭证</b>（库存账实调整），故只反向库存、不红冲凭证。
 *
 * <p>写操作经 {@link StocktakeService#reverse}（CLAUDE.md 原则 1：唯一写入口，绝不绕过领域模型，
 * 库存反向经库存唯一写入口），全程同事务原子。已冲销/未过账单不可冲销；同一单据只能冲销一次
 * （重复被拒）。权限点 inventory:count。
 */
public class ReverseStockCountTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ReverseStockCountTool.class);

    public static final String NAME = "reverse_stock_count";

    private final StocktakeService stocktakeService;

    public ReverseStockCountTool(StocktakeService stocktakeService) {
        this.stocktakeService = Objects.requireNonNull(stocktakeService, "stocktakeService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "冲销盘点单（红字单，不可逆，高风险）：对指定的已过账盘点单 <doc_no> 做整单红冲——"
                + "按原盘盈/盘亏成本对称反向库存（原盘盈差异行反向出库、原盘亏差异行反向入库），"
                + "使该仓商品数量与金额回到盘点前，原盘点单随之转为「已冲销」状态。"
                + "盘点不产生会计凭证（库存账实调整），故只反向库存。"
                + "已冲销/未过账盘点单不可冲销；同一单只能冲销一次（重复被拒）。操作不可逆。"
                + "调用前先在回复正文复述要点（将对已过账盘点单 <doc_no> 按原成本反向盘盈/盘亏库存、原单转已冲销、不可逆）；"
                + "系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"被冲销的盘点单号（如 SC-202606-0001，须为已过账单据）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "inventory:count";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("盘点单号 doc_no 必填");
        }
        String operator = ArchiveToolSupport.operator(context);
        try {
            StockCountDocument document = stocktakeService.reverse(docNo, operator);
            log.info("Agent 冲销盘点单（docNo={}, operator={}, sessionId={}）",
                    docNo, operator, context.sessionId());
            return ToolResult.ok(toData(document));
        } catch (StockCountNotFoundException e) {
            return ToolResult.fail("盘点单不存在: " + docNo);
        } catch (IllegalStateTransitionException | IllegalStateException e) {
            return ToolResult.fail("冲销被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("冲销被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(StockCountDocument document) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", document.getDocNo());
        data.put("status", document.getStatus().name());
        data.put("note", "盘点单已冲销：盘盈/盘亏库存已按原成本对称反向，账实回到盘点前（不可逆）");
        return data;
    }
}
