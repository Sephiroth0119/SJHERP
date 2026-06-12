package com.sjherp.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import com.sjherp.agent.tool.ToolRegistry;
import com.sjherp.app.tool.catalog.CreateProductTool;
import com.sjherp.app.tool.catalog.GetProductDetailTool;
import com.sjherp.app.tool.catalog.SearchProductsTool;
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
 * 领域 Agent 工具装配（M2-T08）：第一批基础档案工具，<b>常驻注册</b>
 * （所有 profile 生效，区别于 dev-only 的演示工具 {@link ToolConfig.DemoToolConfig}）。
 *
 * <p>查询类（NORMAL，不走确认）：search_products / search_customers /
 * search_suppliers / search_warehouses / get_product_detail；
 * 创建类（HIGH，框架强制确认卡片）：create_customer / create_supplier /
 * create_product / create_warehouse。全部经各领域服务唯一写入口执行
 * （CLAUDE.md 原则 1：工具即领域服务，绝不绕过）。
 */
@Configuration
public class DomainToolConfig {

    private static final Logger log = LoggerFactory.getLogger(DomainToolConfig.class);

    DomainToolConfig(ToolRegistry registry,
                     ProductService productService,
                     UnitService unitService,
                     CustomerService customerService,
                     SupplierService supplierService,
                     WarehouseService warehouseService) {
        // 查询类（NORMAL）
        registry.register(new SearchProductsTool(productService, unitService));
        registry.register(new GetProductDetailTool(productService, unitService));
        registry.register(new SearchCustomersTool(customerService));
        registry.register(new SearchSuppliersTool(supplierService));
        registry.register(new SearchWarehousesTool(warehouseService));
        // 创建类（HIGH：档案创建影响主数据，框架强制人工确认）
        registry.register(new CreateProductTool(productService, unitService));
        registry.register(new CreateCustomerTool(customerService));
        registry.register(new CreateSupplierTool(supplierService));
        registry.register(new CreateWarehouseTool(warehouseService));
        log.info("已注册基础档案工具（M2-T08，常驻）：查询 5 个（NORMAL）+ 创建 4 个（HIGH）");
    }
}
