package com.sjherp.app.tool.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.stocktake.StocktakeService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.stocktake.StockCountDocument;
import com.sjherp.domain.stocktake.StockCountLine;
import com.sjherp.domain.stocktake.StockCountNotFoundException;

/**
 * 查询盘点单工具（M3-T03，NORMAL）：按盘点单号查单据头与各行（账面/实盘/差异/状态）。
 * 只读经 {@link StocktakeService#get}（不存在返回友好提示，不抛异常给 LLM）。
 */
public class QueryStockCountTool implements Tool {

    public static final String NAME = "query_stock_count";

    private final StocktakeService stocktakeService;

    public QueryStockCountTool(StocktakeService stocktakeService) {
        this.stocktakeService = Objects.requireNonNull(stocktakeService, "stocktakeService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "按盘点单号查询某张盘点单的明细：单据状态、盘点仓库、各行的账面数量、实盘数量、"
                + "差异（实盘 − 账面，正数盘盈 / 负数盘亏 / 0 无差异）。用户问"
                + "\"某盘点单盘得怎么样\"\"SC-xxx 有没有差异\"时调用。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"盘点单号（如 SC-202606-0001）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("盘点单号 doc_no 不能为空");
        }
        try {
            StockCountDocument document = stocktakeService.get(docNo);
            return ToolResult.ok(toData(document));
        } catch (StockCountNotFoundException e) {
            return ToolResult.fail("未找到盘点单: " + docNo);
        }
    }

    /** 盘点单 → 工具返回数据（数量一律字符串承载；实盘未录入时为 null） */
    private static Map<String, Object> toData(StockCountDocument document) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", document.getDocNo());
        data.put("status", document.getStatus().name());
        data.put("warehouseId", document.getWarehouseId());
        data.put("remark", document.getRemark());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (StockCountLine line : document.getLines()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineNo", line.getLineNo());
            row.put("productId", line.getProductId());
            row.put("snapshotQty", line.getSnapshotQty().toPlainString());
            row.put("countedQty", line.getCountedQty() == null ? null : line.getCountedQty().toPlainString());
            row.put("diffQty", line.diffQty() == null ? null : line.diffQty().toPlainString());
            lines.add(row);
        }
        data.put("lines", lines);
        return data;
    }
}
