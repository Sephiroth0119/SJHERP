package com.sjherp.app.tool.fund;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.fund.PaymentAccount;
import com.sjherp.domain.fund.PaymentAccountCommand;
import com.sjherp.domain.fund.PaymentAccountService;
import com.sjherp.domain.fund.PaymentAccountType;

/**
 * 资金账户档案创建工具（M4-T04a，NORMAL——档案建档，照仓库 create_warehouse 范式）。
 * 写操作经 {@link PaymentAccountService} 唯一入口，编码自动生成（FA-年月-序号），
 * 审计操作人记 agent:&lt;userId&gt;。glAccountCode 必须是已存在/启用/末级 GL 科目，否则被领域服务拒绝。
 */
public class CreatePaymentAccountTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreatePaymentAccountTool.class);

    public static final String NAME = "create_payment_account";

    private final PaymentAccountService paymentAccountService;

    public CreatePaymentAccountTool(PaymentAccountService paymentAccountService) {
        this.paymentAccountService = Objects.requireNonNull(paymentAccountService, "paymentAccountService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "创建资金账户档案（现金/银行账户）：名称必填，账户类别必填（CASH 现金 / BANK 银行 / OTHER 其他货币资金），"
                + "映射的 GL 货币科目编码必填（须为已存在/启用/末级科目，现金常用 1001、银行存款 1002、其他货币资金 1012），"
                + "开户行/银行账号可选，编码自动生成（FA-年月-序号）。用户问\"新增一个银行账户/现金账户\"时调用。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "name":{"type":"string","description":"资金账户名称（必填，200 字以内）"},\
                "accountType":{"type":"string","enum":["CASH","BANK","OTHER"],\
                "description":"账户类别：CASH 库存现金 / BANK 银行存款 / OTHER 其他货币资金（必填）"},\
                "glAccountCode":{"type":"string","description":"映射的 GL 货币科目编码（必填，须为已存在/启用/末级科目，如 1001/1002/1012）"},\
                "bankName":{"type":"string","description":"开户行名称，可选（BANK 账户用）"},\
                "accountNo":{"type":"string","description":"银行账号，可选"}},\
                "required":["name","accountType","glAccountCode"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.NORMAL;
    }

    @Override
    public String requiredPermission() {
        return "finance:payment_account";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        PaymentAccountType accountType;
        try {
            accountType = parseType(ArchiveToolSupport.str(arguments.get("accountType")));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("资金账户创建被拒绝: " + e.getMessage());
        }
        PaymentAccountCommand command = new PaymentAccountCommand(
                null, // 编码自动生成
                ArchiveToolSupport.str(arguments.get("name")),
                accountType,
                ArchiveToolSupport.str(arguments.get("glAccountCode")),
                ArchiveToolSupport.str(arguments.get("bankName")),
                ArchiveToolSupport.str(arguments.get("accountNo")));
        try {
            PaymentAccount account = paymentAccountService.create(command, ArchiveToolSupport.operator(context));
            log.info("Agent 创建资金账户（code={}, name={}, glAccountCode={}, operator={}, sessionId={}）",
                    account.getCode(), account.getName(), account.getGlAccountCode(),
                    ArchiveToolSupport.operator(context), context.sessionId());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", account.getId());
            data.put("code", account.getCode());
            data.put("name", account.getName());
            data.put("accountType", account.getAccountType().label());
            data.put("glAccountCode", account.getGlAccountCode());
            data.put("status", ArchiveToolSupport.statusLabel(account.getStatus()));
            return ToolResult.ok(data);
        } catch (IllegalArgumentException e) {
            // 领域校验拒绝（名称超长、编码冲突、glAccountCode 非末级/停用/不存在等）——宁可拒绝，不可破坏模型
            return ToolResult.fail("资金账户创建被拒绝: " + e.getMessage());
        }
    }

    /** 类别解析（null/非法值给出友好提示，由领域 enum 兜底） */
    private static PaymentAccountType parseType(String accountType) {
        if (accountType == null) {
            throw new IllegalArgumentException("账户类别不能为空（CASH / BANK / OTHER）");
        }
        try {
            return PaymentAccountType.valueOf(accountType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("账户类别仅支持 CASH / BANK / OTHER: " + accountType);
        }
    }
}
