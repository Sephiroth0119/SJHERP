package com.sjherp.app.tool.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.app.transfer.TransferAppService;
import com.sjherp.domain.transfer.TransferDocument;
import com.sjherp.domain.transfer.TransferLine;
import com.sjherp.domain.transfer.TransferNotFoundException;

/**
 * 调拨单查询工具（M3-T04，NORMAL）：按单据号查调拨单头与行项目（调出仓、调入仓、
 * 商品、调拨数量、单据状态）。只读经 {@link TransferAppService#get}。
 *
 * <p>用户问「TR-202606-0001 调拨单到哪一步了」「这张调拨单调了什么」时调用。
 */
public class QueryTransferTool implements Tool {

    public static final String NAME = "query_transfer";

    private final TransferAppService transferAppService;

    public QueryTransferTool(TransferAppService transferAppService) {
        this.transferAppService = Objects.requireNonNull(transferAppService, "transferAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询调拨单：按单据号 doc_no（如 TR-202606-0001）返回调出仓、调入仓、各行商品与"
                + "调拨数量、单据状态。用户问调拨单进度或内容时调用。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"调拨单号（如 TR-202606-0001）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("调拨单号 doc_no 必填");
        }
        try {
            TransferDocument document = transferAppService.get(docNo);
            return ToolResult.ok(toData(document));
        } catch (TransferNotFoundException e) {
            return ToolResult.fail(e.getMessage());
        }
    }

    private static Map<String, Object> toData(TransferDocument document) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", document.getDocNo());
        data.put("status", document.getStatus().name());
        data.put("fromWarehouseId", document.getFromWarehouseId());
        data.put("toWarehouseId", document.getToWarehouseId());
        data.put("remark", document.getRemark());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (TransferLine line : document.getLines()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineNo", line.getLineNo());
            row.put("productId", line.getProductId());
            row.put("quantity", line.getQuantity().toPlainString());
            lines.add(row);
        }
        data.put("lines", lines);
        return data;
    }
}
