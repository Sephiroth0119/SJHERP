package com.sjherp.app.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolRegistry;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.collection.CollectionReceiptAppService;
import com.sjherp.app.consistency.ConsistencyCheckRunner;
import com.sjherp.app.consistency.ConsistencyReportService;
import com.sjherp.app.finance.AgingReportDao;
import com.sjherp.app.finance.FinancialStatementService;
import com.sjherp.app.gl.PeriodCloseService;
import com.sjherp.app.gl.VoucherAppService;
import com.sjherp.app.inventory.InventoryAdjustmentService;
import com.sjherp.app.settlement.SettlementReadAppService;
import com.sjherp.app.payment.PaymentDisbursementAppService;
import com.sjherp.app.production.KittingCheckAppService;
import com.sjherp.app.production.MaterialIssueAppService;
import com.sjherp.app.production.MaterialReturnAppService;
import com.sjherp.app.production.ProductionCostSettlementAppService;
import com.sjherp.app.production.ProductionReportAppService;
import com.sjherp.app.purchase.PurchaseInvoiceAppService;
import com.sjherp.app.purchase.PurchaseOrderAppService;
import com.sjherp.app.purchase.PurchaseReceiptAppService;
import com.sjherp.app.receivable.ReceivableAppService;
import com.sjherp.app.sales.SalesDeliveryAppService;
import com.sjherp.app.sales.SalesInvoiceAppService;
import com.sjherp.app.sales.SalesOrderAppService;
import com.sjherp.app.stocktake.StocktakeService;
import com.sjherp.app.transfer.TransferAppService;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.catalog.UnitService;
import com.sjherp.domain.fund.PaymentAccountService;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.partner.SupplierService;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * HIGH 风险工具权限点反漂移测试（D-8 同批 P2）。
 *
 * <p>背景：AgentLoop 的权限校验在 {@code requiredPermission() == null} 时跳过——
 * HIGH 工具若漏声明权限点，第二道防线（角色权限）消失，仅剩发起人自己的 HITL
 * 确认。本测试把「HIGH 必须声明非空权限点」固化为编译期外的硬约束：
 * 按生产装配方式注册全部工具后逐一断言。
 *
 * <p>覆盖范围：常驻注册的 {@link DomainToolConfig} 全部工具 + dev-only 的演示工具
 * （{@code ToolConfig.DemoToolConfig}，含 DemoHighRiskTool——虽不进生产，但 dev/local
 * 同样有真实用户操作，不豁免）。<b>M3 起新增工具装配类（如单据工具）必须同步加进
 * 本测试的注册清单</b>，否则不受本断言保护。
 */
class HighRiskToolPermissionTest {

    /** 按生产装配方式（各 ToolConfig 构造器）注册全部工具 */
    private static ToolRegistry registryWithAllTools() {
        ToolRegistry registry = new ToolRegistry();
        // 常驻：基础档案工具（M2-T08）+ 库存工具（M3-T01c）+ 盘点（M3-T03）+ 调拨（M3-T04）
        // + 采购订单（M3-T05）+ 销售订单（M3-T08）
        new DomainToolConfig(registry,
                mock(ProductService.class),
                mock(UnitService.class),
                mock(CustomerService.class),
                mock(SupplierService.class),
                mock(WarehouseService.class),
                mock(TransactionalInventoryService.class),
                mock(InventoryAdjustmentService.class),
                mock(StocktakeService.class),
                mock(TransferAppService.class),
                mock(PurchaseOrderAppService.class),
                mock(SalesOrderAppService.class),
                mock(PurchaseReceiptAppService.class),
                mock(PurchaseInvoiceAppService.class),
                mock(SalesDeliveryAppService.class),
                mock(SalesInvoiceAppService.class),
                mock(ReceivableAppService.class),
                mock(ConsistencyCheckRunner.class),
                mock(ConsistencyReportService.class),
                mock(PaymentAccountService.class),
                mock(CollectionReceiptAppService.class),
                mock(PaymentDisbursementAppService.class),
                mock(PeriodCloseService.class),
                mock(VoucherAppService.class),
                mock(AgingReportDao.class),
                mock(FinancialStatementService.class),
                mock(SettlementReadAppService.class));
        // M5-T07 生产模块工具独立装配（设计 D-6：不塞 DomainToolConfig），同样常驻
        new ProductionToolConfig(registry,
                mock(TransactionalWorkOrderService.class),
                mock(MaterialIssueAppService.class),
                mock(MaterialReturnAppService.class),
                mock(KittingCheckAppService.class),
                mock(ProductionReportAppService.class),
                mock(ProductionCostSettlementAppService.class),
                mock(TransactionalMrpService.class));
        // dev-only：演示工具（EchoTool NORMAL + DemoHighRiskTool HIGH），一并纳入断言
        new ToolConfig.DemoToolConfig(registry);
        return registry;
    }

    @Test
    void 所有注册的HIGH风险工具必须声明非空权限点() {
        List<Tool> highRiskTools = registryWithAllTools().all().stream()
                .filter(tool -> tool.riskLevel() == ToolRiskLevel.HIGH)
                .toList();

        assertFalse(highRiskTools.isEmpty(), "至少应注册一个 HIGH 工具（注册清单失效会让本断言空转）");

        for (Tool tool : highRiskTools) {
            String permission = tool.requiredPermission();
            assertTrue(permission != null && !permission.isBlank(),
                    "HIGH 风险工具 " + tool.name() + "（" + tool.getClass().getSimpleName()
                            + "）必须声明非空 requiredPermission——否则 AgentLoop 跳过权限校验，"
                            + "仅剩发起人自己的 HITL 确认，无第二道防线");
        }
    }

    @Test
    void 注册清单覆盖既有工具规模_防注册清单漂移() {
        // M6-T07 基线：常驻 70 个（含 query_consistency_report；查询 37 NORMAL + 资金账户建档 1 NORMAL + 写 32 HIGH；
        // 含 M3-T11 全量 20 工具 [采购收货/发票 10 + 销售出库/发票 10] + M3-T13 run_consistency_check
        // + M4-T04a create_payment_account / search_payment_accounts
        // + M4-T04c 收款单 3 HIGH + 付款单 3 HIGH + query_collection_receipts / query_payment_disbursements 2 NORMAL
        // + M4-T05 precheck_period_close 1 NORMAL + close_accounting_period 1 HIGH
        // + M4-T07a reverse_voucher 1 HIGH
        // + M4-T07b reverse_purchase_receipt / reverse_purchase_invoice 2 HIGH
        // + M4-T07b reverse_sales_delivery / reverse_sales_invoice 2 HIGH
        // + M4-T07c reverse_collection_receipt / reverse_payment_disbursement 2 HIGH
        // + M4-T07c reverse_transfer / reverse_stock_count 2 HIGH
        // + M4-T08 财务只读查询 8 NORMAL：query_receivable_aging / query_payable_aging /
        //   query_balance_sheet / query_income_statement / query_trial_balance /
        //   query_account_balance / query_receivable_settlements / query_payable_settlements）
        // + M5-T07 生产模块 26 个（工单 8 [query_work_order NORMAL + create/create_from_mrp/release/
        //   start/complete/cancel/reverse_work_order 7 HIGH] + 领料 4 [query NORMAL + create/approve/
        //   post 3 HIGH] + 退料 4 [query NORMAL + create/approve/post 3 HIGH] + check_kitting 1 NORMAL
        //   + 报工 4 [query NORMAL + create/approve/post 3 HIGH] + 成本结转 4 [query NORMAL + create/
        //   approve/post 3 HIGH] + query_mrp_run 1 NORMAL）
        // + 演示 2 个（echo + demo_post_document）= 98（全量注册断言基线）。
        // 新增工具装配类后此处会先于权限断言提醒维护注册清单。
        assertTrue(registryWithAllTools().all().size() >= 98,
                "注册工具数少于 M6-T07 基线（98 个）——若调整了工具装配，请同步维护本测试的注册清单");
    }

    /**
     * 每个工具的 parameterSchema() 必须是合法 JSON 对象。
     *
     * <p>背景：OpenAiCompatibleLlmClient.buildRequestBody 把每个工具的 parameterSchema 解析为 JSON
     * 塞进 tools 数组；任一工具 schema 非法 JSON 会令<b>整个</b> LLM 请求构建抛 LlmClientException，
     * 导致每轮对话都返回「AI 服务暂时不可用」。Mockito 单测只验 riskLevel/permission/execute，
     * 从不解析 schema 字符串，故此类 bug（如文本块内误写 Java 字符串拼接 {@code " + CONST + "}）
     * 只在完整 LLM 调用时显现。本测试把「schema 可解析」固化为编译期外硬约束。
     */
    @Test
    void 所有注册工具的parameterSchema必须是合法JSON() {
        ObjectMapper mapper = new ObjectMapper();
        for (Tool tool : registryWithAllTools().all()) {
            String schema = tool.parameterSchema();
            if (schema == null || schema.isBlank()) {
                continue; // 无参工具允许空 schema
            }
            try {
                var node = mapper.readTree(schema);
                assertTrue(node.isObject(),
                        "工具 " + tool.name() + "（" + tool.getClass().getSimpleName()
                                + "）的 parameterSchema 必须是 JSON 对象，实际为 " + node.getNodeType());
            } catch (Exception e) {
                throw new AssertionError("工具 " + tool.name() + "（" + tool.getClass().getSimpleName()
                        + "）的 parameterSchema 不是合法 JSON——会导致整个 LLM 请求构建失败、"
                        + "每轮对话返回「AI 服务暂时不可用」。常见原因：文本块（\"\"\"...\"\"\"）内误写"
                        + " Java 字符串拼接 \" + 常量 + \"（文本块不会插值）。原始异常：" + e.getMessage(), e);
            }
        }
    }
}
