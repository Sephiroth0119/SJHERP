package com.sjherp.app.tool.sales;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.receivable.ReceivableAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.app.tool.sales.SalesToolSupport.Resolution;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.partner.Customer;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.receivable.AccountsReceivable;
import com.sjherp.domain.receivable.ReceivableQuery;
import com.sjherp.domain.receivable.ReceivableStatus;

/**
 * 查询应收账款工具（M3-T11，NORMAL，登录即可无权限点）：按客户（可选名称或编码）和状态
 * （可选 OPEN/PARTIAL/SETTLED）分页查询应收账款，返回最多 10 条明细与合计。
 *
 * <p>只读经 {@link ReceivableAppService#search}，
 * requiredPermission 返回 null（登录即可）。
 */
public class QueryReceivablesTool implements Tool {

    public static final String NAME = "query_receivables";

    private final CustomerService customerService;
    private final ReceivableAppService receivableAppService;

    public QueryReceivablesTool(CustomerService customerService,
                                ReceivableAppService receivableAppService) {
        this.customerService = Objects.requireNonNull(customerService, "customerService 不能为空");
        this.receivableAppService = Objects.requireNonNull(receivableAppService,
                "receivableAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询应收账款：按客户（可选名称或编码）和状态（可选 OPEN/PARTIAL/SETTLED）分页查询"
                + "客户欠企业的款项，返回最多 10 条明细（来源单据号、金额、到期日、状态）与汇总总额。"
                + "用户问某客户还欠多少钱、应收账款情况时调用。登录即可，无需额外权限。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "customer":{"type":"string","description":"客户名称或编码（可选，省略则查全部）"},\
                "status":{"type":"string","enum":["OPEN","PARTIAL","SETTLED"],\
                "description":"应收状态过滤（可选：OPEN 未核销 / PARTIAL 部分核销 / SETTLED 已核销）"}},\
                "additionalProperties":false}""";
    }

    @Override
    public String requiredPermission() {
        return null;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        Long customerId = null;
        String customerText = ArchiveToolSupport.str(arguments.get("customer"));
        if (customerText != null) {
            Resolution<Customer> customerRes = SalesToolSupport.resolveCustomer(
                    customerService, customerText);
            if (customerRes.failed()) {
                return ToolResult.fail("客户解析失败: " + customerRes.error());
            }
            customerId = customerRes.value().getId();
        }

        ReceivableStatus status = null;
        String statusText = ArchiveToolSupport.str(arguments.get("status"));
        if (statusText != null) {
            try {
                status = ReceivableStatus.valueOf(statusText.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ToolResult.fail("应收状态不合法（支持 OPEN/PARTIAL/SETTLED）: " + statusText);
            }
        }

        ReceivableQuery query = new ReceivableQuery(customerId, status, 1, ArchiveToolSupport.MAX_ITEMS);
        PageResult<AccountsReceivable> result = receivableAppService.search(query);

        return ToolResult.ok(toData(result));
    }

    private static Map<String, Object> toData(PageResult<AccountsReceivable> result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.total());
        List<Map<String, Object>> items = new ArrayList<>();
        for (AccountsReceivable receivable : result.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", receivable.getId());
            row.put("customerId", receivable.getCustomerId());
            row.put("sourceDocNo", receivable.getSourceDocNo());
            row.put("amount", receivable.getAmount().toPlainString());
            row.put("dueDate", receivable.getDueDate() == null ? null : receivable.getDueDate().toString());
            row.put("status", receivable.getStatus().name());
            row.put("settledAmount", receivable.getSettledAmount().toPlainString());
            row.put("openAmount", receivable.openAmount().toPlainString());
            items.add(row);
        }
        data.put("items", items);
        return data;
    }
}
