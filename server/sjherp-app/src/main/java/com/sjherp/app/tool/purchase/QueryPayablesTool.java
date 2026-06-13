package com.sjherp.app.tool.purchase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.purchase.PurchaseInvoiceAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.app.tool.purchase.PurchaseToolSupport.Resolution;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.partner.Supplier;
import com.sjherp.domain.partner.SupplierService;
import com.sjherp.domain.payable.AccountsPayable;
import com.sjherp.domain.payable.AccountsPayableQuery;
import com.sjherp.domain.payable.PayableStatus;

/**
 * 查询应付账款工具（M3-T11，NORMAL，登录即可无权限点）：按供应商（可选名称或编码）和状态
 * （可选 OPEN/PARTIAL/SETTLED）分页查询应付账款，返回最多 10 条明细与合计。
 *
 * <p>只读经 {@link PurchaseInvoiceAppService#searchPayables}，
 * requiredPermission 返回 null（登录即可）。
 */
public class QueryPayablesTool implements Tool {

    public static final String NAME = "query_payables";

    private final SupplierService supplierService;
    private final PurchaseInvoiceAppService purchaseInvoiceAppService;

    public QueryPayablesTool(SupplierService supplierService,
                             PurchaseInvoiceAppService purchaseInvoiceAppService) {
        this.supplierService = Objects.requireNonNull(supplierService, "supplierService 不能为空");
        this.purchaseInvoiceAppService = Objects.requireNonNull(purchaseInvoiceAppService,
                "purchaseInvoiceAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询应付账款：按供应商（可选名称或编码）和状态（可选 OPEN/PARTIAL/SETTLED）分页查询"
                + "欠供应商的款项，返回最多 10 条明细（来源单据号、金额、到期日、状态）与汇总总额。"
                + "用户问欠某供应商多少钱、应付账款情况时调用。登录即可，无需额外权限。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "supplier":{"type":"string","description":"供应商名称或编码（可选，省略则查全部）"},\
                "status":{"type":"string","enum":["OPEN","PARTIAL","SETTLED"],\
                "description":"应付状态过滤（可选：OPEN 未核销 / PARTIAL 部分核销 / SETTLED 已核销）"}},\
                "additionalProperties":false}""";
    }

    @Override
    public String requiredPermission() {
        return null;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        Long supplierId = null;
        String supplierText = ArchiveToolSupport.str(arguments.get("supplier"));
        if (supplierText != null) {
            Resolution<Supplier> supplierRes = PurchaseToolSupport.resolveSupplier(
                    supplierService, supplierText);
            if (supplierRes.failed()) {
                return ToolResult.fail("供应商解析失败: " + supplierRes.error());
            }
            supplierId = supplierRes.value().getId();
        }

        PayableStatus status = null;
        String statusText = ArchiveToolSupport.str(arguments.get("status"));
        if (statusText != null) {
            try {
                status = PayableStatus.valueOf(statusText.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ToolResult.fail("应付状态不合法（支持 OPEN/PARTIAL/SETTLED）: " + statusText);
            }
        }

        AccountsPayableQuery query = new AccountsPayableQuery(supplierId, status, 1, ArchiveToolSupport.MAX_ITEMS);
        PageResult<AccountsPayable> result = purchaseInvoiceAppService.searchPayables(query);

        return ToolResult.ok(toData(result));
    }

    private static Map<String, Object> toData(PageResult<AccountsPayable> result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.total());
        List<Map<String, Object>> items = new ArrayList<>();
        for (AccountsPayable payable : result.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", payable.getId());
            row.put("supplierId", payable.getSupplierId());
            row.put("sourceDocNo", payable.getSourceDocNo());
            row.put("amount", payable.getAmount().toPlainString());
            row.put("dueDate", payable.getDueDate() == null ? null : payable.getDueDate().toString());
            row.put("status", payable.getStatus().name());
            row.put("settledAmount", payable.getSettledAmount().toPlainString());
            row.put("outstandingAmount", payable.outstandingAmount().toPlainString());
            items.add(row);
        }
        data.put("items", items);
        return data;
    }
}
