package com.sjherp.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import com.sjherp.agent.tool.ToolRegistry;
import com.sjherp.app.inventory.InventoryAdjustmentService;
import com.sjherp.app.purchase.PurchaseOrderAppService;
import com.sjherp.app.sales.SalesOrderAppService;
import com.sjherp.app.stocktake.StocktakeService;
import com.sjherp.app.tool.catalog.CreateProductTool;
import com.sjherp.app.tool.catalog.GetProductDetailTool;
import com.sjherp.app.tool.catalog.SearchProductsTool;
import com.sjherp.app.tool.inventory.AdjustInventoryTool;
import com.sjherp.app.tool.inventory.CreateStockCountTool;
import com.sjherp.app.tool.inventory.CreateTransferTool;
import com.sjherp.app.tool.inventory.QueryInventoryBalanceTool;
import com.sjherp.app.tool.inventory.QueryStockCountTool;
import com.sjherp.app.tool.inventory.QueryTransferTool;
import com.sjherp.app.tool.partner.CreateCustomerTool;
import com.sjherp.app.tool.partner.CreateSupplierTool;
import com.sjherp.app.tool.partner.SearchCustomersTool;
import com.sjherp.app.tool.partner.SearchSuppliersTool;
import com.sjherp.app.tool.purchase.CreatePurchaseOrderTool;
import com.sjherp.app.tool.purchase.QueryPurchaseOrderTool;
import com.sjherp.app.tool.sales.CreateSalesOrderTool;
import com.sjherp.app.tool.sales.QuerySalesOrderTool;
import com.sjherp.app.tool.warehouse.CreateWarehouseTool;
import com.sjherp.app.tool.warehouse.SearchWarehousesTool;
import com.sjherp.app.transfer.TransferAppService;
import com.sjherp.app.purchase.PurchaseReceiptAppService;
import com.sjherp.app.purchase.PurchaseInvoiceAppService;
import com.sjherp.app.sales.SalesDeliveryAppService;
import com.sjherp.app.sales.SalesInvoiceAppService;
import com.sjherp.app.receivable.ReceivableAppService;
import com.sjherp.app.consistency.ConsistencyCheckService;
import com.sjherp.app.tool.purchase.ApprovePurchaseOrderTool;
import com.sjherp.app.tool.purchase.CreatePurchaseReceiptTool;
import com.sjherp.app.tool.purchase.ApprovePurchaseReceiptTool;
import com.sjherp.app.tool.purchase.PostPurchaseReceiptTool;
import com.sjherp.app.tool.purchase.QueryPurchaseReceiptTool;
import com.sjherp.app.tool.purchase.CreatePurchaseInvoiceTool;
import com.sjherp.app.tool.purchase.ApprovePurchaseInvoiceTool;
import com.sjherp.app.tool.purchase.PostPurchaseInvoiceTool;
import com.sjherp.app.tool.purchase.QueryPurchaseInvoiceTool;
import com.sjherp.app.tool.purchase.QueryPayablesTool;
import com.sjherp.app.tool.sales.ApproveSalesOrderTool;
import com.sjherp.app.tool.sales.CreateSalesDeliveryTool;
import com.sjherp.app.tool.sales.ApproveSalesDeliveryTool;
import com.sjherp.app.tool.sales.PostSalesDeliveryTool;
import com.sjherp.app.tool.sales.QuerySalesDeliveryTool;
import com.sjherp.app.tool.sales.CreateSalesInvoiceTool;
import com.sjherp.app.tool.sales.ApproveSalesInvoiceTool;
import com.sjherp.app.tool.sales.PostSalesInvoiceTool;
import com.sjherp.app.tool.sales.QuerySalesInvoiceTool;
import com.sjherp.app.tool.sales.QueryReceivablesTool;
import com.sjherp.app.tool.consistency.RunConsistencyCheckTool;
import com.sjherp.app.tool.fund.CreatePaymentAccountTool;
import com.sjherp.app.tool.fund.SearchPaymentAccountsTool;
import com.sjherp.app.tool.collection.CreateCollectionReceiptTool;
import com.sjherp.app.tool.collection.ApproveCollectionReceiptTool;
import com.sjherp.app.tool.collection.PostCollectionReceiptTool;
import com.sjherp.app.tool.collection.QueryCollectionReceiptsTool;
import com.sjherp.app.tool.payment.CreatePaymentDisbursementTool;
import com.sjherp.app.tool.payment.ApprovePaymentDisbursementTool;
import com.sjherp.app.tool.payment.PostPaymentDisbursementTool;
import com.sjherp.app.tool.payment.QueryPaymentDisbursementsTool;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.catalog.UnitService;
import com.sjherp.app.collection.CollectionReceiptAppService;
import com.sjherp.app.payment.PaymentDisbursementAppService;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.fund.PaymentAccountService;
import com.sjherp.domain.partner.SupplierService;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 领域 Agent 工具装配（M2-T08 基础档案 + M3-T01c 库存 + M3-T03 盘点 + M3-T04 调拨
 * + M3-T05/T06/T07 采购线 + M3-T08/T09/T10 销售线 + M3-T11 进销存工具全量
 * + M3-T13 一致性校验 + M4-T04a 资金账户档案 + M4-T04c 收付款单 Agent 工具），
 * <b>常驻注册</b> 50 个（所有 profile 生效，区别于
 * dev-only 的演示工具 {@link ToolConfig.DemoToolConfig}）。完整清单见 docs/领域工具清单.md。
 *
 * <p>查询类（NORMAL，登录即可）20 个：search_products / get_product_detail /
 * search_customers / search_suppliers / search_warehouses / query_inventory_balance /
 * query_stock_count / query_transfer / query_purchase_order / query_sales_order /
 * query_purchase_receipt / query_purchase_invoice / query_payables /
 * query_sales_delivery / query_sales_invoice / query_receivables / run_consistency_check /
 * search_payment_accounts / query_collection_receipts / query_payment_disbursements；
 * 建档类（NORMAL）1 个：create_payment_account（M4-T04a，
 * glAccountCode 须为末级启用 GL 科目）；
 * 写类（HIGH，框架强制确认卡片）29 个：create_customer / create_supplier /
 * create_product / create_warehouse / adjust_inventory / create_stock_count /
 * create_transfer / create_purchase_order / create_sales_order + M3-T11 采购线
 * （approve_purchase_order / create·approve·post_purchase_receipt /
 * create·approve·post_purchase_invoice）+ M3-T11 销售线（approve_sales_order /
 * create·approve·post_sales_delivery / create·approve·post_sales_invoice）
 * + M4-T04c 收款单（create·approve·post_collection_receipt）
 * + M4-T04c 付款单（create·approve·post_payment_disbursement）。
 * 全部经各领域/应用服务唯一写入口执行（CLAUDE.md 原则 1：工具即领域服务，绝不绕过）；
 * 建单·审核(approve)·过账(post) 各为独立 HIGH 工具，忠于状态机与职责分离。
 * 收付款单建·审·过账涉及资金过账与核销，均为独立 HIGH（HITL 确认，框架级）。
 *
 * <p>⚠️ 新增工具后必须同步：{@code HighRiskToolPermissionTest} 注册清单与数量基线、
 * docs/领域工具清单.md、LlmAgent 系统提示词「当前业务能力」段。
 */
@Configuration
public class DomainToolConfig {

    private static final Logger log = LoggerFactory.getLogger(DomainToolConfig.class);

    DomainToolConfig(ToolRegistry registry,
                     ProductService productService,
                     UnitService unitService,
                     CustomerService customerService,
                     SupplierService supplierService,
                     WarehouseService warehouseService,
                     TransactionalInventoryService transactionalInventoryService,
                     InventoryAdjustmentService inventoryAdjustmentService,
                     StocktakeService stocktakeService,
                     TransferAppService transferAppService,
                     PurchaseOrderAppService purchaseOrderAppService,
                     SalesOrderAppService salesOrderAppService,
                     PurchaseReceiptAppService purchaseReceiptAppService,
                     PurchaseInvoiceAppService purchaseInvoiceAppService,
                     SalesDeliveryAppService salesDeliveryAppService,
                     SalesInvoiceAppService salesInvoiceAppService,
                     ReceivableAppService receivableAppService,
                     ConsistencyCheckService consistencyCheckService,
                     PaymentAccountService paymentAccountService,
                     CollectionReceiptAppService collectionReceiptAppService,
                     PaymentDisbursementAppService paymentDisbursementAppService) {
        // 查询类（NORMAL）
        registry.register(new SearchProductsTool(productService, unitService));
        registry.register(new GetProductDetailTool(productService, unitService));
        registry.register(new SearchCustomersTool(customerService));
        registry.register(new SearchSuppliersTool(supplierService));
        registry.register(new SearchWarehousesTool(warehouseService));
        registry.register(new QueryInventoryBalanceTool(warehouseService, productService,
                transactionalInventoryService));
        registry.register(new QueryStockCountTool(stocktakeService));
        registry.register(new QueryTransferTool(transferAppService));
        registry.register(new QueryPurchaseOrderTool(purchaseOrderAppService));
        registry.register(new QuerySalesOrderTool(salesOrderAppService));
        // 进销存只读查询（M3-T11，NORMAL，登录即可）
        registry.register(new QueryPurchaseReceiptTool(purchaseReceiptAppService));
        registry.register(new QueryPurchaseInvoiceTool(purchaseInvoiceAppService));
        registry.register(new QueryPayablesTool(supplierService, purchaseInvoiceAppService));
        registry.register(new QuerySalesDeliveryTool(salesDeliveryAppService));
        registry.register(new QuerySalesInvoiceTool(salesInvoiceAppService));
        registry.register(new QueryReceivablesTool(customerService, receivableAppService));
        // 数据一致性交叉校验（M3-T13，NORMAL，只读勾稽报告）
        registry.register(new RunConsistencyCheckTool(consistencyCheckService));
        // 资金账户查询（M4-T04a，NORMAL，登录即可）
        registry.register(new SearchPaymentAccountsTool(paymentAccountService));
        // 收/付款单查询（M4-T04c，NORMAL，登录即可）
        registry.register(new QueryCollectionReceiptsTool(collectionReceiptAppService));
        registry.register(new QueryPaymentDisbursementsTool(paymentDisbursementAppService));
        // 写类（HIGH：影响主数据/产生库存流水/形成业务承诺，框架强制人工确认）
        registry.register(new CreateProductTool(productService, unitService));
        registry.register(new CreateCustomerTool(customerService));
        registry.register(new CreateSupplierTool(supplierService));
        registry.register(new CreateWarehouseTool(warehouseService));
        registry.register(new AdjustInventoryTool(warehouseService, productService,
                inventoryAdjustmentService));
        registry.register(new CreateStockCountTool(warehouseService, productService, stocktakeService));
        registry.register(new CreateTransferTool(warehouseService, productService, transferAppService));
        registry.register(new CreatePurchaseOrderTool(supplierService, productService,
                purchaseOrderAppService));
        registry.register(new CreateSalesOrderTool(customerService, productService,
                salesOrderAppService));
        // 采购收货线（M3-T11，HIGH：审核/收货/过账影响库存与采购承诺）
        registry.register(new ApprovePurchaseOrderTool(purchaseOrderAppService));
        registry.register(new CreatePurchaseReceiptTool(warehouseService, purchaseReceiptAppService));
        registry.register(new ApprovePurchaseReceiptTool(purchaseReceiptAppService));
        registry.register(new PostPurchaseReceiptTool(purchaseReceiptAppService));
        // 采购发票/应付线（M3-T11，HIGH：开票/审核/过账形成应付）
        registry.register(new CreatePurchaseInvoiceTool(purchaseInvoiceAppService));
        registry.register(new ApprovePurchaseInvoiceTool(purchaseInvoiceAppService));
        registry.register(new PostPurchaseInvoiceTool(purchaseInvoiceAppService));
        // 销售出库线（M3-T11，HIGH：审核/出库/过账扣库存结转 COGS）
        registry.register(new ApproveSalesOrderTool(salesOrderAppService));
        registry.register(new CreateSalesDeliveryTool(warehouseService, productService, salesDeliveryAppService));
        registry.register(new ApproveSalesDeliveryTool(salesDeliveryAppService));
        registry.register(new PostSalesDeliveryTool(salesDeliveryAppService));
        // 销售发票/应收线（M3-T11，HIGH：开票/审核/过账形成应收）
        registry.register(new CreateSalesInvoiceTool(productService, salesInvoiceAppService));
        registry.register(new ApproveSalesInvoiceTool(salesInvoiceAppService));
        registry.register(new PostSalesInvoiceTool(salesInvoiceAppService));
        // 资金账户建档（M4-T04a，NORMAL：档案建档，glAccountCode 须为末级启用 GL 科目）
        registry.register(new CreatePaymentAccountTool(paymentAccountService));
        // 收款单建/审/过账线（M4-T04c，HIGH：涉及资金过账与应收核销，HITL 确认）
        registry.register(new CreateCollectionReceiptTool(collectionReceiptAppService));
        registry.register(new ApproveCollectionReceiptTool(collectionReceiptAppService));
        registry.register(new PostCollectionReceiptTool(collectionReceiptAppService));
        // 付款单建/审/过账线（M4-T04c，HIGH：涉及资金过账与应付核销，HITL 确认）
        registry.register(new CreatePaymentDisbursementTool(paymentDisbursementAppService));
        registry.register(new ApprovePaymentDisbursementTool(paymentDisbursementAppService));
        registry.register(new PostPaymentDisbursementTool(paymentDisbursementAppService));
        log.info("已注册领域工具（M2-T08 档案 + M3-T01c 库存 + M3-T03 盘点 + M3-T04 调拨"
                + " + M3-T05/T06/T07 采购线 + M3-T08/T09/T10 销售线 + M3-T11 全量注册"
                + " + M3-T13 一致性校验 + M4-T04a 资金账户 + M4-T04c 收付款单，常驻）："
                + "查询 20 个（NORMAL）+ 资金账户建档 1 个（NORMAL）+ 写 29 个（HIGH）");
    }
}
