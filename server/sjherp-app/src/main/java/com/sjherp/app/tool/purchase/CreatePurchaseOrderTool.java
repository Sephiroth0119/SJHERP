package com.sjherp.app.tool.purchase;

import java.math.BigDecimal;
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
import com.sjherp.app.purchase.PurchaseDtos.PurchaseOrderLineRequest;
import com.sjherp.app.purchase.PurchaseOrderAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.app.tool.purchase.PurchaseToolSupport.Resolution;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.partner.Supplier;
import com.sjherp.domain.partner.SupplierService;
import com.sjherp.domain.purchase.PurchaseOrder;

/**
 * 采购订单建单工具（M3-T05，HIGH——影响采购承诺，框架强制确认卡片）：单次建一张单行采购订单
 * （向某供应商采购一个商品）。建单后单据为草稿（下单不动库存），需经审核才可被收货引用。
 *
 * <p>写操作经 {@link PurchaseOrderAppService} → 领域 PurchaseOrderService（CLAUDE.md 原则 1）；
 * 单据号 PO- 自动编号；审计操作人记 agent:&lt;userId&gt;。权限点 purchase:order
 * （ADMIN/BOSS/PURCHASER）。供应商/商品停用、数量 ≤ 0、单价为负一律拒绝。
 *
 * <p>收货（采购入库单）/ 发票工具留 M3-T11（本工具仅建采购订单）。
 */
public class CreatePurchaseOrderTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreatePurchaseOrderTool.class);

    public static final String NAME = "create_purchase_order";

    private final SupplierService supplierService;
    private final ProductService productService;
    private final PurchaseOrderAppService purchaseOrderAppService;

    public CreatePurchaseOrderTool(SupplierService supplierService, ProductService productService,
                                   PurchaseOrderAppService purchaseOrderAppService) {
        this.supplierService = Objects.requireNonNull(supplierService, "supplierService 不能为空");
        this.productService = Objects.requireNonNull(productService, "productService 不能为空");
        this.purchaseOrderAppService = Objects.requireNonNull(purchaseOrderAppService,
                "purchaseOrderAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "创建采购订单（单行）：向某供应商（supplier）采购一个商品（product），数量 quantity"
                + "（基本单位，正数）、采购单价 unit_price（≥0）。供应商与商品传名称或编码。"
                + "建单后单据为草稿、不动库存（采购订单只是对供应商的采购承诺），需后续审核、收货、"
                + "开票才形成入库与应付。调用前先在回复正文复述要点（供应商、商品、数量、单价）；"
                + "系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "supplier":{"type":"string","description":"供应商名称或编码"},\
                "product":{"type":"string","description":"商品名称或编码"},\
                "quantity":{"type":"string","description":"订购数量（基本单位，正数，字符串承载，如 \\"100\\"）"},\
                "unit_price":{"type":"string","description":"采购单价（≥0，字符串承载，如 \\"12.50\\"）"},\
                "remark":{"type":"string","description":"采购说明（可选）"}},\
                "required":["supplier","product","quantity","unit_price"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "purchase:order";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        Resolution<Supplier> supplier = PurchaseToolSupport.resolveSupplier(
                supplierService, ArchiveToolSupport.str(arguments.get("supplier")));
        if (supplier.failed()) {
            return ToolResult.fail("供应商解析失败: " + supplier.error());
        }
        Resolution<Product> product = PurchaseToolSupport.resolveProduct(
                productService, ArchiveToolSupport.str(arguments.get("product")));
        if (product.failed()) {
            return ToolResult.fail(product.error());
        }
        BigDecimal quantity = PurchaseToolSupport.decimal(arguments.get("quantity"));
        if (quantity == null) {
            return ToolResult.fail("订购数量 quantity 必填且为正数");
        }
        BigDecimal unitPrice = PurchaseToolSupport.decimal(arguments.get("unit_price"));
        if (unitPrice == null) {
            return ToolResult.fail("采购单价 unit_price 必填（≥0）");
        }

        String operator = ArchiveToolSupport.operator(context);
        try {
            PurchaseOrder order = purchaseOrderAppService.create(
                    supplier.value().getId(), null, ArchiveToolSupport.str(arguments.get("remark")),
                    List.of(new PurchaseOrderLineRequest(product.value().getId(), quantity, unitPrice)),
                    operator);
            log.info("Agent 创建采购订单（docNo={}, supplier={}, product={}, qty={}, price={}, operator={}, sessionId={}）",
                    order.getDocNo(), supplier.value().getCode(), product.value().getCode(),
                    quantity.toPlainString(), unitPrice.toPlainString(), operator, context.sessionId());
            return ToolResult.ok(toData(order, supplier.value(), product.value(), quantity, unitPrice));
        } catch (IllegalArgumentException e) {
            // 领域校验拒绝（停用、数量/单价非法等）——宁可拒绝，不可破坏模型
            return ToolResult.fail("采购订单创建被拒绝: " + e.getMessage());
        }
    }

    /** 建单结果 → 工具返回数据（数量金额一律字符串承载） */
    private static Map<String, Object> toData(PurchaseOrder order, Supplier supplier, Product product,
                                              BigDecimal quantity, BigDecimal unitPrice) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", order.getDocNo());
        data.put("status", order.getStatus().name());
        data.put("supplier", supplier.getName());
        data.put("product", product.getName());
        data.put("quantity", quantity.toPlainString());
        data.put("unitPrice", unitPrice.toPlainString());
        data.put("totalAmount", order.totalAmount().toPlainString());
        data.put("note", "采购订单已创建为草稿（未动库存），需审核后才可收货");
        return data;
    }
}
