package com.sjherp.app.tool.fund;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.fund.PaymentAccount;
import com.sjherp.domain.fund.PaymentAccountQuery;
import com.sjherp.domain.fund.PaymentAccountService;

/**
 * 资金账户档案查询工具（M4-T04a，NORMAL，登录即可，照仓库 search_warehouses 范式）：
 * 关键字 + 状态过滤，返回精简列表（最多 10 条）。查询经 {@link PaymentAccountService} 唯一入口。
 */
public class SearchPaymentAccountsTool implements Tool {

    public static final String NAME = "search_payment_accounts";

    private final PaymentAccountService paymentAccountService;

    public SearchPaymentAccountsTool(PaymentAccountService paymentAccountService) {
        this.paymentAccountService = Objects.requireNonNull(paymentAccountService, "paymentAccountService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询资金账户档案（现金/银行账户）：按关键字（模糊匹配编码/名称/开户行）和状态过滤，"
                + "返回精简列表（编码/名称/类别/GL科目/开户行/账号/状态，最多 10 条）。"
                + "用户问\"有哪些资金账户/银行账户\"\"查一下某账户\"时调用。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "keyword":{"type":"string","description":"关键字，模糊匹配资金账户编码/名称/开户行；不传表示不按关键字过滤"},\
                "status":{"type":"string","enum":["ENABLED","DISABLED"],\
                "description":"档案状态过滤：ENABLED 启用 / DISABLED 停用；不传表示全部"}},\
                "required":[],"additionalProperties":false}""";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        PageResult<PaymentAccount> result = paymentAccountService.search(new PaymentAccountQuery(
                ArchiveToolSupport.str(arguments.get("keyword")),
                ArchiveToolSupport.parseStatus(arguments.get("status")),
                1, ArchiveToolSupport.MAX_ITEMS));

        List<Map<String, Object>> items = new ArrayList<>();
        for (PaymentAccount account : result.items()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", account.getId());
            item.put("code", account.getCode());
            item.put("name", account.getName());
            item.put("accountType", account.getAccountType().label());
            item.put("glAccountCode", account.getGlAccountCode());
            item.put("bankName", account.getBankName());
            item.put("accountNo", account.getAccountNo());
            item.put("status", ArchiveToolSupport.statusLabel(account.getStatus()));
            items.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.total());
        data.put("items", items);
        if (result.total() > items.size()) {
            data.put("note", "共 " + result.total() + " 条，仅返回前 " + items.size()
                    + " 条，请引导用户补充关键字缩小范围");
        }
        return ToolResult.ok(data);
    }
}
