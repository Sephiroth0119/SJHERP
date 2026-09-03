package com.sjherp.app.tool.production;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.production.KittingCheckAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.production.KittingCheck;
import com.sjherp.domain.production.KittingCheckLine;

/**
 * 齐套检查（只读，M5-T07，NORMAL，production:material）。
 *
 * <p>检查指定工单在目标仓库是否所有子件库存充足，并返回各行缺料明细。
 */
public class CheckKittingTool implements Tool {

    private final KittingCheckAppService kittingCheckAppService;

    public CheckKittingTool(KittingCheckAppService kittingCheckAppService) {
        this.kittingCheckAppService = Objects.requireNonNull(kittingCheckAppService, "kittingCheckAppService");
    }

    @Override
    public String name() { return "check_kitting"; }

    @Override
    public String description() {
        return "对工单执行齐套检查（只读），判断指定仓库中所有子件库存是否满足领料需求。"
                + "返回 kitted（true=全套齐套）和各行缺料明细。"
                + "必填：work_order_doc_no（工单单号）、warehouse_id（检查仓库 ID）。";
    }

    @Override
    public ToolRiskLevel riskLevel() { return ToolRiskLevel.NORMAL; }

    @Override
    public String requiredPermission() { return "production:material"; }

    @Override
    public String parameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "work_order_doc_no": { "type": "string",  "description": "工单单号（WO- 前缀）" },
                    "warehouse_id":      { "type": "integer", "description": "检查仓库 ID" }
                  },
                  "required": ["work_order_doc_no", "warehouse_id"]
                }
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        try {
            String workOrderDocNo = ArchiveToolSupport.str(arguments.get("work_order_doc_no"));
            if (workOrderDocNo == null) return ToolResult.fail("work_order_doc_no 不能为空");
            long warehouseId = ProductionToolSupport.longId(arguments.get("warehouse_id"), "warehouse_id");

            KittingCheck check = kittingCheckAppService.check(workOrderDocNo, warehouseId);
            return ToolResult.ok(toData(check));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("齐套检查失败：" + e.getMessage());
        }
    }

    private static Map<String, Object> toData(KittingCheck check) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("work_order_doc_no", check.workOrderDocNo());
        data.put("warehouse_id", check.warehouseId());
        data.put("kitted", check.kitted());
        data.put("lines", check.lines().stream().map(CheckKittingTool::lineData).toList());
        return data;
    }

    private static Map<String, Object> lineData(KittingCheckLine line) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("product_id", line.productId());
        m.put("unit_id", line.unitId());
        m.put("required", line.required().toPlainString());
        m.put("available", line.available().toPlainString());
        m.put("shortage", line.shortage().toPlainString());
        return m;
    }
}
