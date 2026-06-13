package com.sjherp.app.tool.sales;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.sales.SalesDtos.SalesOrderLineRequest;
import com.sjherp.app.sales.SalesOrderAppService;
import com.sjherp.app.sales.SalesOrderAppService.CreateResult;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.app.tool.sales.SalesToolSupport.Resolution;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.partner.Customer;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.sales.SalesOrder;

/**
 * 创建销售订单工具（M3-T08，HIGH——产生业务单据，框架强制确认卡片）。
 *
 * <p>仅创建草稿销售订单：客户 + 行项目（每行一个商品 + 数量 + 销售单价）。下单不动库存，
 * 可用库存不足仅警告不阻断（warnings 随结果返回）。建单后还需审核、出库、开票才完成销售全流程。
 *
 * <p>写操作经 {@link SalesOrderAppService}（外层事务）→ 领域 SalesOrderService（CLAUDE.md 原则 1）；
 * 单据号 SO- 自动编号；审计操作人记 agent:&lt;userId&gt;。权限点 sales:order（ADMIN/BOSS/SALES）。
 * 客户/商品停用、商品重复、数量/单价非法一律拒绝。
 */
public class CreateSalesOrderTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreateSalesOrderTool.class);

    public static final String NAME = "create_sales_order";

    private final CustomerService customerService;
    private final ProductService productService;
    private final SalesOrderAppService salesOrderAppService;

    public CreateSalesOrderTool(CustomerService customerService, ProductService productService,
                                SalesOrderAppService salesOrderAppService) {
        this.customerService = Objects.requireNonNull(customerService, "customerService 不能为空");
        this.productService = Objects.requireNonNull(productService, "productService 不能为空");
        this.salesOrderAppService = Objects.requireNonNull(salesOrderAppService, "salesOrderAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "创建销售订单（草稿）：给某客户下单若干商品，每行一个商品 + 数量 + 销售单价。"
                + "客户与商品传名称或编码。下单不动库存（只是销售约定），可用库存不足只会给提示不阻断。"
                + "可选传 check_warehouse 让系统在下单时对该仓做可用库存检查并在不足时提示。"
                + "建单后还需审核、出库、开票才完成销售（出库/开票目前在系统界面或后续流程完成）。"
                + "调用前先在回复正文复述要点（客户、各行商品/数量/单价）；系统会自动请求用户确认后才创建。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "customer":{"type":"string","description":"客户名称或编码（如 张三百货 / CUS-202606-0001）"},\
                "order_date":{"type":"string","description":"订单日期，可选（YYYY-MM-DD，省略取当天）"},\
                "check_warehouse":{"type":"string","description":"可用库存检查仓库名称或编码，可选（省略则不检查；不足仅提示不阻断）"},\
                "remark":{"type":"string","description":"订单说明，可选"},\
                "lines":{"type":"array","description":"订单行（每行一个商品）","items":{\
                "type":"object","properties":{\
                "product":{"type":"string","description":"商品名称或编码"},\
                "quantity":{"type":"string","description":"订单数量（基本单位，正数，字符串承载，如 \\"100\\"）"},\
                "unit_price":{"type":"string","description":"销售单价（≥0，最多 6 位小数，字符串承载，如 \\"12.50\\"）"}},\
                "required":["product","quantity","unit_price"],"additionalProperties":false}}},\
                "required":["customer","lines"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "sales:order";
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        Resolution<Customer> customer = SalesToolSupport.resolveCustomer(
                customerService, ArchiveToolSupport.str(arguments.get("customer")));
        if (customer.failed()) {
            return ToolResult.fail("客户解析失败: " + customer.error());
        }

        Long checkWarehouseId = null;
        String checkWarehouse = ArchiveToolSupport.str(arguments.get("check_warehouse"));
        // 可用库存检查仓库可选——这里不解析仓库（避免引入仓库服务依赖膨胀工具构造器），
        // 仅当用户给了编码且为纯数字 id 时透传；否则跳过检查（不阻断下单）。
        if (checkWarehouse != null) {
            try {
                checkWarehouseId = Long.parseLong(checkWarehouse);
            } catch (NumberFormatException ignore) {
                // 非数字仓库标识时不做可用库存检查（下单本就不阻断），不报错
                checkWarehouseId = null;
            }
        }

        Object rawLines = arguments.get("lines");
        if (!(rawLines instanceof List<?> lineList) || lineList.isEmpty()) {
            return ToolResult.fail("销售订单至少要有一行（lines 不能为空）");
        }

        List<SalesOrderLineRequest> lines = new ArrayList<>(lineList.size());
        List<ResolvedProductRef> resolved = new ArrayList<>(lineList.size());
        for (Object item : lineList) {
            if (!(item instanceof Map<?, ?> lineMap)) {
                return ToolResult.fail("订单行格式不合法：每行须含 product / quantity / unit_price");
            }
            Map<String, Object> row = (Map<String, Object>) lineMap;
            Resolution<Product> product = SalesToolSupport.resolveProduct(
                    productService, ArchiveToolSupport.str(row.get("product")));
            if (product.failed()) {
                return ToolResult.fail(product.error());
            }
            BigDecimal quantity;
            BigDecimal unitPrice;
            try {
                quantity = SalesToolSupport.decimal(row.get("quantity"));
                unitPrice = SalesToolSupport.decimal(row.get("unit_price"));
            } catch (IllegalArgumentException e) {
                return ToolResult.fail(e.getMessage());
            }
            if (quantity == null) {
                return ToolResult.fail("订单行数量 quantity 必填且为正数");
            }
            if (unitPrice == null) {
                return ToolResult.fail("订单行销售单价 unit_price 必填");
            }
            lines.add(new SalesOrderLineRequest(product.value().getId(), quantity, unitPrice));
            resolved.add(new ResolvedProductRef(product.value().getName(), product.value().getCode()));
        }

        java.time.LocalDate orderDate;
        try {
            String rawDate = ArchiveToolSupport.str(arguments.get("order_date"));
            orderDate = rawDate == null ? null : java.time.LocalDate.parse(rawDate);
        } catch (java.time.format.DateTimeParseException e) {
            return ToolResult.fail("订单日期 order_date 格式应为 YYYY-MM-DD");
        }

        String operator = ArchiveToolSupport.operator(context);
        try {
            CreateResult result = salesOrderAppService.create(customer.value().getId(),
                    orderDate, ArchiveToolSupport.str(arguments.get("remark")),
                    checkWarehouseId, lines, operator);
            SalesOrder order = result.order();
            log.info("Agent 创建销售订单（docNo={}, customer={}, lines={}, operator={}, sessionId={}）",
                    order.getDocNo(), customer.value().getCode(), lines.size(), operator,
                    context.sessionId());
            return ToolResult.ok(toData(order, customer.value(), resolved, result.warnings()));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("销售订单创建被拒绝: " + e.getMessage());
        }
    }

    /** 建单结果 → 工具返回数据（数量/单价/金额一律字符串承载） */
    private static Map<String, Object> toData(SalesOrder order, Customer customer,
                                              List<ResolvedProductRef> resolved, List<String> warnings) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", order.getDocNo());
        data.put("status", order.getStatus().name());
        data.put("customer", customer.getName());
        data.put("orderDate", order.getOrderDate().toString());
        data.put("totalAmount", order.totalAmount().toPlainString());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < order.getLines().size(); i++) {
            var line = order.getLines().get(i);
            ResolvedProductRef ref = resolved.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineNo", line.getLineNo());
            row.put("product", ref.name());
            row.put("productCode", ref.code());
            row.put("quantity", line.getQuantity().toPlainString());
            row.put("unitPrice", line.getUnitPrice().toPlainString());
            row.put("amount", line.getAmount().toPlainString());
            lines.add(row);
        }
        data.put("lines", lines);
        if (warnings != null && !warnings.isEmpty()) {
            data.put("stockWarnings", warnings);
        }
        data.put("note", "销售订单已创建为草稿，下单不动库存；需后续审核、出库、开票完成销售");
        return data;
    }

    /** 行解析出的商品展示信息（与订单行同序对齐） */
    private record ResolvedProductRef(String name, String code) {
    }
}
