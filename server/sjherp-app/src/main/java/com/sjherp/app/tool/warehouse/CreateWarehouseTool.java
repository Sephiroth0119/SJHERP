package com.sjherp.app.tool.warehouse;

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
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseCommand;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 仓库档案创建工具（M2-T08，HIGH——档案创建影响主数据，框架强制确认卡片）。
 * 写操作经 {@link WarehouseService} 唯一入口，编码自动生成（WH-年月-序号），
 * 审计操作人记 agent:&lt;userId&gt;。库位管理本批不开放（界面维护，M3 落地库位表）。
 */
public class CreateWarehouseTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreateWarehouseTool.class);

    public static final String NAME = "create_warehouse";

    private final WarehouseService warehouseService;

    public CreateWarehouseTool(WarehouseService warehouseService) {
        this.warehouseService = Objects.requireNonNull(warehouseService, "warehouseService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "创建仓库档案：名称必填，地址/负责人可选，编码自动生成（WH-年月-序号）。"
                + "调用前先在回复正文中向用户复述要创建的仓库要点；系统会自动请求用户确认后才真正执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "name":{"type":"string","description":"仓库名称（必填，100 字以内）"},\
                "address":{"type":"string","description":"仓库地址，可选"},\
                "manager":{"type":"string","description":"负责人，可选"}},\
                "required":["name"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "warehouse:create_warehouse";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        WarehouseCommand command = new WarehouseCommand(
                null, // 编码自动生成
                ArchiveToolSupport.str(arguments.get("name")),
                ArchiveToolSupport.str(arguments.get("address")),
                ArchiveToolSupport.str(arguments.get("manager")),
                null); // 库位管理本批不开放给聊天创建
        try {
            Warehouse warehouse = warehouseService.create(command, ArchiveToolSupport.operator(context));
            log.info("Agent 创建仓库（code={}, name={}, operator={}, sessionId={}）",
                    warehouse.getCode(), warehouse.getName(),
                    ArchiveToolSupport.operator(context), context.sessionId());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", warehouse.getId());
            data.put("code", warehouse.getCode());
            data.put("name", warehouse.getName());
            data.put("status", ArchiveToolSupport.statusLabel(warehouse.getStatus()));
            return ToolResult.ok(data);
        } catch (IllegalArgumentException e) {
            // 领域校验拒绝（名称超长、编码冲突等）——宁可拒绝，不可破坏模型
            return ToolResult.fail("仓库创建被拒绝: " + e.getMessage());
        }
    }
}
