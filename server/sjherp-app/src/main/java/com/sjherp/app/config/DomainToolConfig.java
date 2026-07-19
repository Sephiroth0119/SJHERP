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
import com.sjherp.app.tool.inventory.ReverseStockCountTool;
import com.sjherp.app.tool.inventory.ReverseTransferTool;
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
import com.sjherp.app.consistency.ConsistencyCheckRunner;
import com.sjherp.app.consistency.ConsistencyReportService;
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
import com.sjherp.app.tool.purchase.ReversePurchaseReceiptTool;
import com.sjherp.app.tool.purchase.ReversePurchaseInvoiceTool;
import com.sjherp.app.tool.sales.ApproveSalesOrderTool;
import com.sjherp.app.tool.sales.CreateSalesDeliveryTool;
import com.sjherp.app.tool.sales.ApproveSalesDeliveryTool;
import com.sjherp.app.tool.sales.PostSalesDeliveryTool;
import com.sjherp.app.tool.sales.ReverseSalesDeliveryTool;
import com.sjherp.app.tool.sales.QuerySalesDeliveryTool;
import com.sjherp.app.tool.sales.CreateSalesInvoiceTool;
import com.sjherp.app.tool.sales.ApproveSalesInvoiceTool;
import com.sjherp.app.tool.sales.PostSalesInvoiceTool;
import com.sjherp.app.tool.sales.ReverseSalesInvoiceTool;
import com.sjherp.app.tool.sales.QuerySalesInvoiceTool;
import com.sjherp.app.tool.sales.QueryReceivablesTool;
import com.sjherp.app.tool.consistency.RunConsistencyCheckTool;
import com.sjherp.app.tool.consistency.QueryConsistencyReportTool;
import com.sjherp.app.tool.gl.CloseAccountingPeriodTool;
import com.sjherp.app.tool.gl.PrecheckPeriodCloseTool;
import com.sjherp.app.tool.gl.ReverseVoucherTool;
import com.sjherp.app.tool.fund.CreatePaymentAccountTool;
import com.sjherp.app.tool.fund.SearchPaymentAccountsTool;
import com.sjherp.app.tool.collection.CreateCollectionReceiptTool;
import com.sjherp.app.tool.collection.ApproveCollectionReceiptTool;
import com.sjherp.app.tool.collection.PostCollectionReceiptTool;
import com.sjherp.app.tool.collection.ReverseCollectionReceiptTool;
import com.sjherp.app.tool.collection.QueryCollectionReceiptsTool;
import com.sjherp.app.tool.payment.CreatePaymentDisbursementTool;
import com.sjherp.app.tool.payment.ApprovePaymentDisbursementTool;
import com.sjherp.app.tool.payment.PostPaymentDisbursementTool;
import com.sjherp.app.tool.payment.ReversePaymentDisbursementTool;
import com.sjherp.app.tool.payment.QueryPaymentDisbursementsTool;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.catalog.UnitService;
import com.sjherp.app.collection.CollectionReceiptAppService;
import com.sjherp.app.payment.PaymentDisbursementAppService;
import com.sjherp.app.finance.AgingReportDao;
import com.sjherp.app.finance.FinancialStatementService;
import com.sjherp.app.gl.PeriodCloseService;
import com.sjherp.app.gl.VoucherAppService;
import com.sjherp.app.settlement.SettlementReadAppService;
import com.sjherp.app.tool.finance.QueryBalanceSheetTool;
import com.sjherp.app.tool.finance.QueryIncomeStatementTool;
import com.sjherp.app.tool.finance.QueryPayableAgingTool;
import com.sjherp.app.tool.finance.QueryPayableSettlementsTool;
import com.sjherp.app.tool.finance.QueryReceivableAgingTool;
import com.sjherp.app.tool.finance.QueryReceivableSettlementsTool;
import com.sjherp.app.tool.gl.QueryAccountBalanceTool;
import com.sjherp.app.tool.gl.QueryTrialBalanceTool;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.fund.PaymentAccountService;
import com.sjherp.domain.partner.SupplierService;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 领域 Agent 工具装配（M2-T08 基础档案 + M3-T01c 库存 + M3-T03 盘点 + M3-T04 调拨
 * + M3-T05/T06/T07 采购线 + M3-T08/T09/T10 销售线 + M3-T11 进销存工具全量
 * + M3-T13 一致性校验 + M4-T04a 资金账户档案 + M4-T04c 收付款单 Agent 工具
 * + M4-T05 月末结转关账 Agent 工具 + M4-T07a 凭证冲销 Agent 工具
 * + M4-T07b 采购/销售单据冲销 Agent 工具
 * + M4-T07c 收付款单/调拨单/盘点单冲销 Agent 工具
 * + M4-T08 财务只读查询工具），
 * <b>常驻注册</b> 70 个（37 个 NORMAL 查询 + 1 个 NORMAL 建档 + 32 个 HIGH 写工具，
 * 所有 profile 生效，区别于 dev-only 的演示工具 {@link ToolConfig.DemoToolConfig} 2 个）。
 * 生产模块 26 个工具独立装配在 {@link ProductionToolConfig}（设计 D-6），同样常驻；
 * 全量常驻 70+26=96，含演示 96+2=98。完整清单见 docs/领域工具清单.md。
 *
 * <p>查询类（NORMAL）37 个（多为登录即可，财务报表/账龄/凭证/核销类带 finance:* 权限点）：
 * search_products / get_product_detail /
 * search_customers / search_suppliers / search_warehouses / query_inventory_balance /
 * query_stock_count / query_transfer / query_purchase_order / query_sales_order /
 * query_purchase_receipt / query_purchase_invoice / query_payables /
 * query_sales_delivery / query_sales_invoice / query_receivables / run_consistency_check /
 * query_consistency_report（M6-T07，历史报告只读召回与解释）/
 * search_payment_accounts / query_collection_receipts / query_payment_disbursements /
 * precheck_period_close（M4-T05，关账可行性预检，只读）/
 * query_receivable_aging / query_payable_aging（M4-T08，账龄报告，finance:settlement）/
 * query_balance_sheet / query_income_statement（M4-T08，资产负债表/利润表，finance:report）/
 * query_trial_balance / query_account_balance（M4-T08，试算平衡/科目余额，finance:voucher）/
 * query_receivable_settlements / query_payable_settlements（M4-T08，核销流水，finance:settlement）；
 * 建档类（NORMAL）1 个：create_payment_account（M4-T04a，
 * glAccountCode 须为末级启用 GL 科目）；
 * 写类（HIGH，框架强制确认卡片）32 个：create_customer / create_supplier /
 * create_product / create_warehouse / adjust_inventory / create_stock_count /
 * create_transfer / create_purchase_order / create_sales_order + M3-T11 采购线
 * （approve_purchase_order / create·approve·post_purchase_receipt /
 * create·approve·post_purchase_invoice）+ M3-T11 销售线（approve_sales_order /
 * create·approve·post_sales_delivery / create·approve·post_sales_invoice）
 * + M4-T04c 收款单（create·approve·post_collection_receipt）
 * + M4-T04c 付款单（create·approve·post_payment_disbursement）
 * + M4-T05 月末结转关账（close_accounting_period）
 * + M4-T07a 凭证冲销（reverse_voucher）
 * + M4-T07b 采购单据冲销（reverse_purchase_receipt / reverse_purchase_invoice）
 * + M4-T07b 销售单据冲销（reverse_sales_delivery / reverse_sales_invoice）。
 * 全部经各领域/应用服务唯一写入口执行（CLAUDE.md 原则 1：工具即领域服务，绝不绕过）；
 * 建单·审核(approve)·过账(post) 各为独立 HIGH 工具，忠于状态机与职责分离。
 * 收付款单建·审·过账涉及资金过账与核销，均为独立 HIGH（HITL 确认，框架级）。
 * 月末关账（close_accounting_period）为最高风险路径，独立 HIGH（不可逆，HITL 确认）。
 * 凭证冲销（reverse_voucher）生成借贷对调红字凭证、原凭证转已冲销，独立 HIGH（不可逆，HITL 确认）。
 * 采购单据冲销（reverse_purchase_receipt / reverse_purchase_invoice）红冲已过账入库/发票——反向库存、
 * 回退子账量、冲回应付、红冲自动凭证、原单转已冲销，独立 HIGH（不可逆，HITL 确认）。
 * 销售单据冲销（reverse_sales_delivery / reverse_sales_invoice）红冲已过账出库/发票——库存按原 COGS 反向入库、
 * 回退发货/开票量、冲回应收（须无核销）、红冲自动凭证、原单转已冲销，独立 HIGH（不可逆，HITL 确认）。
 * 收付款单冲销（reverse_collection_receipt / reverse_payment_disbursement）红冲已过账收/付款单——反向核销应收/应付、
 * 红冲现金侧凭证、原单转已冲销（解锁对应发票红冲），独立 HIGH（不可逆，HITL 确认）。
 * 调拨/盘点单冲销（reverse_transfer / reverse_stock_count）红冲已过账调拨/盘点单——按原成本对称反向库存
 * （调拨不出凭证、盘点不出凭证），原单转已冲销，独立 HIGH（不可逆，HITL 确认）。
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
                     ConsistencyCheckRunner consistencyCheckRunner,
                     ConsistencyReportService consistencyReportService,
                     PaymentAccountService paymentAccountService,
                     CollectionReceiptAppService collectionReceiptAppService,
                     PaymentDisbursementAppService paymentDisbursementAppService,
                     PeriodCloseService periodCloseService,
                     VoucherAppService voucherAppService,
                     AgingReportDao agingReportDao,
                     FinancialStatementService financialStatementService,
                     SettlementReadAppService settlementReadAppService) {
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
        // 数据一致性交叉校验（M6-T05，NORMAL，不改业务账；显式保存运行报告）
        registry.register(new RunConsistencyCheckTool(consistencyCheckRunner));
        // 历史报告召回（M6-T07，NORMAL，登录即可；只读不重新运行）
        registry.register(new QueryConsistencyReportTool(consistencyReportService));
        // 资金账户查询（M4-T04a，NORMAL，登录即可）
        registry.register(new SearchPaymentAccountsTool(paymentAccountService));
        // 收/付款单查询（M4-T04c，NORMAL，登录即可）
        registry.register(new QueryCollectionReceiptsTool(collectionReceiptAppService));
        registry.register(new QueryPaymentDisbursementsTool(paymentDisbursementAppService));
        // 月末关账预检（M4-T05，NORMAL：只读跑结转预览/一致性/试算平衡，不写不过账）
        registry.register(new PrecheckPeriodCloseTool(periodCloseService));
        // 财务只读查询（M4-T08，NORMAL，纯封装已有只读服务，零新增领域逻辑）
        registry.register(new QueryReceivableAgingTool(agingReportDao));
        registry.register(new QueryPayableAgingTool(agingReportDao));
        registry.register(new QueryBalanceSheetTool(financialStatementService));
        registry.register(new QueryIncomeStatementTool(financialStatementService));
        registry.register(new QueryTrialBalanceTool(voucherAppService));
        registry.register(new QueryAccountBalanceTool(voucherAppService));
        registry.register(new QueryReceivableSettlementsTool(settlementReadAppService));
        registry.register(new QueryPayableSettlementsTool(settlementReadAppService));
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
        // 销售单据冲销（M4-T07b，HIGH：红字单——反向库存按原 COGS/回退发货量/冲回应收/红冲凭证，不可逆，HITL 确认）
        registry.register(new ReverseSalesDeliveryTool(salesDeliveryAppService));
        registry.register(new ReverseSalesInvoiceTool(salesInvoiceAppService));
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
        // 月末结转关账（M4-T05，HIGH：最高风险路径，结转损益+关账不可逆，HITL 确认）
        registry.register(new CloseAccountingPeriodTool(periodCloseService));
        // 凭证冲销（M4-T07a，HIGH：红字凭证借贷对调，原凭证转已冲销，不可逆，HITL 确认）
        registry.register(new ReverseVoucherTool(voucherAppService));
        // 采购单据冲销（M4-T07b，HIGH：红字单——反向库存/回退子账量/冲回应付/红冲凭证，不可逆，HITL 确认）
        registry.register(new ReversePurchaseReceiptTool(purchaseReceiptAppService));
        registry.register(new ReversePurchaseInvoiceTool(purchaseInvoiceAppService));
        // 收付款单冲销（M4-T07c，HIGH：红字单——反向核销应收/应付 + 红冲现金侧凭证，原单转已冲销，不可逆，HITL 确认）
        registry.register(new ReverseCollectionReceiptTool(collectionReceiptAppService));
        registry.register(new ReversePaymentDisbursementTool(paymentDisbursementAppService));
        // 调拨/盘点单冲销（M4-T07c，HIGH：红字单——按原成本对称反向库存，不出 GL 凭证，原单转已冲销，不可逆，HITL 确认）
        registry.register(new ReverseTransferTool(transferAppService));
        registry.register(new ReverseStockCountTool(stocktakeService));
        // 注：M5-T07 生产模块 Agent 工具（26 个）独立装配在 ProductionToolConfig（设计 D-6：
        // 本类已临界爆炸，生产工具不再塞入），同样常驻。
        log.info("已注册领域工具（M2-T08 档案 + M3-T01c 库存 + M3-T03 盘点 + M3-T04 调拨"
                + " + M3-T05/T06/T07 采购线 + M3-T08/T09/T10 销售线 + M3-T11 全量注册"
                + " + M3-T13 一致性校验 + M4-T04a 资金账户 + M4-T04c 收付款单"
                + " + M4-T05 月末结转关账 + M4-T07a 凭证冲销 + M4-T07b 采购/销售单据冲销"
                + " + M4-T07c 收付款单/调拨单/盘点单冲销 + M4-T08 财务只读查询"
                + " + M6-T07 一致性报告召回，常驻）："
                + "查询 37 个（NORMAL）+ 资金账户建档 1 个（NORMAL）+ 写 32 个（HIGH）");
    }
}
