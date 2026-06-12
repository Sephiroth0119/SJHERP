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
import com.sjherp.domain.partner.Customer;
import com.sjherp.domain.partner.CustomerCommand;
import com.sjherp.domain.partner.CustomerService;

/**
 * 客户档案创建工具（M2-T08，HIGH——档案创建影响主数据，框架强制确认卡片）。
 * 写操作经 {@link CustomerService} 唯一入口，编码自动生成（CUS-年月-序号），
 * 结算方式缺省为月结（MONTHLY），审计操作人记 agent:&lt;userId&gt;。
 */
public class CreateCustomerTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreateCustomerTool.class);

    public static final String NAME = "create_customer";

    private final CustomerService customerService;

    public CreateCustomerTool(CustomerService customerService) {
        this.customerService = Objects.requireNonNull(customerService, "customerService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "创建客户档案：名称必填，联系人/电话/地址/税号可选，结算方式可选（不传默认月结 MONTHLY），"
                + "编码自动生成（CUS-年月-序号）。调用前先在回复正文中向用户复述要创建的客户要点；"
                + "系统会自动请求用户确认后才真正执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "name":{"type":"string","description":"客户名称（必填，200 字以内）"},\
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
        return "partner:create_customer";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        CustomerCommand command = new CustomerCommand(
                null, // 编码自动生成
                ArchiveToolSupport.str(arguments.get("name")),
                ArchiveToolSupport.str(arguments.get("contact_person")),
                ArchiveToolSupport.str(arguments.get("contact_phone")),
                ArchiveToolSupport.str(arguments.get("address")),
                ArchiveToolSupport.str(arguments.get("tax_no")),
                ArchiveToolSupport.parseSettlementOrDefault(arguments.get("settlement_method")),
                null); // 信用额度本批不开放给聊天创建（界面维护）
        try {
            Customer customer = customerService.create(command, ArchiveToolSupport.operator(context));
            log.info("Agent 创建客户（code={}, name={}, operator={}, sessionId={}）",
                    customer.getCode(), customer.getName(),
                    ArchiveToolSupport.operator(context), context.sessionId());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", customer.getId());
            data.put("code", customer.getCode());
            data.put("name", customer.getName());
            data.put("settlementMethod", ArchiveToolSupport.settlementLabel(customer.getSettlementMethod()));
            data.put("status", ArchiveToolSupport.statusLabel(customer.getStatus()));
            return ToolResult.ok(data);
        } catch (IllegalArgumentException e) {
            // 领域校验拒绝（名称超长、编码冲突等）——宁可拒绝，不可破坏模型
            return ToolResult.fail("客户创建被拒绝: " + e.getMessage());
        }
    }
}
