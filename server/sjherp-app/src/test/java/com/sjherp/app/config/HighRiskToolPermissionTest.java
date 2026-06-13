package com.sjherp.app.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolRegistry;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.inventory.InventoryAdjustmentService;
import com.sjherp.app.stocktake.StocktakeService;
import com.sjherp.app.transfer.TransferAppService;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.catalog.UnitService;
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
        new DomainToolConfig(registry,
                mock(ProductService.class),
                mock(UnitService.class),
                mock(CustomerService.class),
                mock(SupplierService.class),
                mock(WarehouseService.class),
                mock(TransactionalInventoryService.class),
                mock(InventoryAdjustmentService.class),
                mock(StocktakeService.class),
                mock(TransferAppService.class));
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
        // M3-T04 基线：常驻 15 个（查询 8 NORMAL：含 query_inventory_balance /
        // query_stock_count / query_transfer；写 7 HIGH：含 adjust_inventory /
        // create_stock_count / create_transfer）+ 演示 2 个（echo + demo_post_document）= 17。
        // 新增工具装配类后此处会先于权限断言提醒维护注册清单。
        assertTrue(registryWithAllTools().all().size() >= 17,
                "注册工具数少于 M3-T04 基线（17 个）——若调整了工具装配，请同步维护本测试的注册清单");
    }
}
