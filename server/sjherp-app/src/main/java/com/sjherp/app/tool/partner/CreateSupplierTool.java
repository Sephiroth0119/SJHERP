package com.sjherp.app.tool.partner;

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
import com.sjherp.domain.partner.Supplier;
import com.sjherp.domain.partner.SupplierCommand;
import com.sjherp.domain.partner.SupplierService;

/**
 * 供应商档案创建工具（M2-T08，HIGH——档案创建影响主数据，框架强制确认卡片）。
 * 写操作经 {@link SupplierService} 唯一入口，编码自动生成（SUP-年月-序号），
 * 结算方式缺省为月结（MONTHLY），审计操作人记 agent:&lt;userId&gt;。
 */
public class CreateSupplierTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreateSupplierTool.class);

    public static final String NAME = "create_supplier";

    private final SupplierService supplierService;

    public CreateSupplierTool(SupplierService supplierService) {
        this.supplierService = Objects.requireNonNull(supplierService, "supplierService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "创建供应商档案：名称必填，联系人/电话/地址/税号可选，结算方式可选（不传默认月结 MONTHLY），"
                + "编码自动生成（SUP-年月-序号）。调用前先在回复正文中向用户复述要创建的供应商要点；"
                + "系统会自动请求用户确认后才真正执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "name":{"type":"string","description":"供应商名称（必填，200 字以内）"},\
                "contact_person":{"type":"string","description":"联系人，可选"},\
                "contact_phone":{"type":"string","description":"联系电话，可选"},\
                "address":{"type":"string","description":"地址，可选"},\
                "tax_no":{"type":"string","description":"税号，可选"},\
                "settlement_method":{"type":"string","enum":["MONTHLY","CASH","PREPAID"],\
                "description":"结算方式：MONTHLY 月结（默认）/ CASH 现结 / PREPAID 预付；用户没提就不传"}},\
                "required":["name"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "partner:create_supplier";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        SupplierCommand command = new SupplierCommand(
                null, // 编码自动生成
                ArchiveToolSupport.str(arguments.get("name")),
                ArchiveToolSupport.str(arguments.get("contact_person")),
                ArchiveToolSupport.str(arguments.get("contact_phone")),
                ArchiveToolSupport.str(arguments.get("address")),
                ArchiveToolSupport.str(arguments.get("tax_no")),
                ArchiveToolSupport.parseSettlementOrDefault(arguments.get("settlement_method")));
        try {
            Supplier supplier = supplierService.create(command, ArchiveToolSupport.operator(context));
            log.info("Agent 创建供应商（code={}, name={}, operator={}, sessionId={}）",
                    supplier.getCode(), supplier.getName(),
                    ArchiveToolSupport.operator(context), context.sessionId());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", supplier.getId());
            data.put("code", supplier.getCode());
            data.put("name", supplier.getName());
            data.put("settlementMethod", ArchiveToolSupport.settlementLabel(supplier.getSettlementMethod()));
            data.put("status", ArchiveToolSupport.statusLabel(supplier.getStatus()));
            return ToolResult.ok(data);
        } catch (IllegalArgumentException e) {
            // 领域校验拒绝（名称超长、编码冲突等）——宁可拒绝，不可破坏模型
            return ToolResult.fail("供应商创建被拒绝: " + e.getMessage());
        }
    }
}
