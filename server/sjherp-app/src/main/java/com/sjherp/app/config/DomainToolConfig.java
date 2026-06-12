package com.sjherp.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import com.sjherp.agent.tool.ToolRegistry;
import com.sjherp.app.inventory.InventoryAdjustmentService;
import com.sjherp.app.tool.catalog.CreateProductTool;
import com.sjherp.app.tool.catalog.GetProductDetailTool;
import com.sjherp.app.tool.catalog.SearchProductsTool;
import com.sjherp.app.tool.inventory.AdjustInventoryTool;
import com.sjherp.app.tool.inventory.QueryInventoryBalanceTool;
import com.sjherp.app.tool.partner.CreateCustomerTool;
import com.sjherp.app.tool.partner.CreateSupplierTool;
import com.sjherp.app.tool.partner.SearchCustomersTool;
import com.sjherp.app.tool.partner.SearchSuppliersTool;
import com.sjherp.app.tool.warehouse.CreateWarehouseTool;
import com.sjherp.app.tool.warehouse.SearchWarehousesTool;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.catalog.UnitService;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.partner.SupplierService;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 领域 Agent 工具装配（M2-T08 基础档案 + M3-T01c 库存），<b>常驻注册</b>
 * （所有 profile 生效，区别于 dev-only 的演示工具 {@link ToolConfig.DemoToolConfig}）。
 *
 * <p>查询类（NORMAL，不走确认）：search_products / search_customers /
 * search_suppliers / search_warehouses / get_product_detail /
 * query_inventory_balance；
 * 写类（HIGH，框架强制确认卡片）：create_customer / create_supplier /
 * create_product / create_warehouse / adjust_inventory。全部经各领域服务
 * 唯一写入口执行（CLAUDE.md 原则 1：工具即领域服务，绝不绕过）。
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
                     InventoryAdjustmentService inventoryAdjustmentService) {
        // 查询类（NORMAL）
        registry.register(new SearchProductsTool(productService, unitService));
        registry.register(new GetProductDetailTool(productService, unitService));
        registry.register(new SearchCustomersTool(customerService));
        registry.register(new SearchSuppliersTool(supplierService));
        registry.register(new SearchWarehousesTool(warehouseService));
        registry.register(new QueryInventoryBalanceTool(warehouseService, productService,
                transactionalInventoryService));
        // 写类（HIGH：影响主数据/产生库存流水，框架强制人工确认）
        registry.register(new CreateProductTool(productService, unitService));
        registry.register(new CreateCustomerTool(customerService));
        registry.register(new CreateSupplierTool(supplierService));
        registry.register(new CreateWarehouseTool(warehouseService));
        registry.register(new AdjustInventoryTool(warehouseService, productService,
                inventoryAdjustmentService));
        log.info("已注册领域工具（M2-T08 档案 + M3-T01c 库存，常驻）：查询 6 个（NORMAL）+ 写 5 个（HIGH）");
    }
}
