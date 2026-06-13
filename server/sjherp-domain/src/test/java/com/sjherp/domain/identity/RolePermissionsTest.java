package com.sjherp.domain.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 角色 → 权限点静态映射测试（M2-T06）：矩阵逐行勾稽 docs/权限矩阵.md，
 * 改映射必须同步改本测试与文档。
 */
class RolePermissionsTest {

    // ---------------------------------------------------------------- 矩阵逐行

    @Test
    void ADMIN_拥有全部权限点() {
        assertEquals(EnumSet.allOf(Permission.class), RolePermissions.permissionsOf(Role.ADMIN));
    }

    @Test
    void BOSS_拥有全部业务权限点_不含演示权限() {
        Set<Permission> boss = RolePermissions.permissionsOf(Role.BOSS);
        assertEquals(EnumSet.of(
                Permission.CATALOG_CREATE_PRODUCT,
                Permission.PARTNER_CREATE_CUSTOMER,
                Permission.PARTNER_CREATE_SUPPLIER,
                Permission.WAREHOUSE_CREATE_WAREHOUSE,
                Permission.CATALOG_WRITE,
                Permission.PARTNER_WRITE,
                Permission.WAREHOUSE_WRITE,
                Permission.INVENTORY_ADJUST,
                Permission.INVENTORY_COUNT,
                Permission.INVENTORY_TRANSFER,
                Permission.PURCHASE_ORDER,
                Permission.PURCHASE_RECEIPT,
                Permission.PURCHASE_INVOICE,
                Permission.SALES_ORDER,
                Permission.SALES_DELIVERY,
                Permission.SALES_INVOICE,
                Permission.GAP_TRIAGE), boss);
        assertFalse(boss.contains(Permission.DEMO_POST_DOCUMENT));
    }

    @Test
    void PURCHASER_创建供应商加采购全程() {
        assertEquals(EnumSet.of(
                        Permission.PARTNER_CREATE_SUPPLIER,
                        Permission.PURCHASE_ORDER,
                        Permission.PURCHASE_RECEIPT,
                        Permission.PURCHASE_INVOICE),
                RolePermissions.permissionsOf(Role.PURCHASER));
    }

    @Test
    void SALES_创建客户加销售全线() {
        assertEquals(EnumSet.of(
                        Permission.PARTNER_CREATE_CUSTOMER,
                        Permission.SALES_ORDER,
                        Permission.SALES_DELIVERY,
                        Permission.SALES_INVOICE),
                RolePermissions.permissionsOf(Role.SALES));
    }

    @Test
    void WAREHOUSE_仓库域全部加库存调整盘点调拨加采购收货销售发货() {
        assertEquals(EnumSet.of(Permission.WAREHOUSE_CREATE_WAREHOUSE, Permission.WAREHOUSE_WRITE,
                        Permission.INVENTORY_ADJUST, Permission.INVENTORY_COUNT,
                        Permission.INVENTORY_TRANSFER,
                        Permission.PURCHASE_RECEIPT, Permission.SALES_DELIVERY),
                RolePermissions.permissionsOf(Role.WAREHOUSE));
    }

    @Test
    void ACCOUNTANT_采购发票与销售发票_其余财务权限留M4() {
        assertEquals(EnumSet.of(Permission.PURCHASE_INVOICE, Permission.SALES_INVOICE),
                RolePermissions.permissionsOf(Role.ACCOUNTANT));
    }

    @Test
    void 每个角色都有映射项() {
        for (Role role : Role.values()) {
            assertNotNull(RolePermissions.permissionsOf(role), "角色缺少权限映射: " + role);
        }
    }

    // ---------------------------------------------------------------- 判定语义

    @Test
    void 仓管不可创建客户_管理员可以() {
        assertFalse(RolePermissions.isGranted(Set.of(Role.WAREHOUSE), Permission.PARTNER_CREATE_CUSTOMER));
        assertTrue(RolePermissions.isGranted(Set.of(Role.ADMIN), Permission.PARTNER_CREATE_CUSTOMER));
    }

    @Test
    void 多角色取并集() {
        Set<Role> salesPlusWarehouse = Set.of(Role.SALES, Role.WAREHOUSE);
        assertTrue(RolePermissions.isGranted(salesPlusWarehouse, Permission.PARTNER_CREATE_CUSTOMER));
        assertTrue(RolePermissions.isGranted(salesPlusWarehouse, Permission.WAREHOUSE_WRITE));
        assertFalse(RolePermissions.isGranted(salesPlusWarehouse, Permission.PARTNER_CREATE_SUPPLIER));
    }

    @Test
    void 空角色集合一律拒绝() {
        assertFalse(RolePermissions.isGranted(Set.of(), Permission.PARTNER_CREATE_CUSTOMER));
        assertFalse(RolePermissions.isGranted(null, Permission.PARTNER_CREATE_CUSTOMER));
    }

    @Test
    void 按code判定_与工具声明的字符串一致() {
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.SALES), "partner:create_customer"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.PURCHASER), "partner:create_supplier"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.WAREHOUSE), "warehouse:create_warehouse"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.ADMIN), "catalog:create_product"));
        // 库存调整（M3-T01c）：ADMIN/BOSS/WAREHOUSE 持有，其余角色拒绝
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.WAREHOUSE), "inventory:adjust"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.BOSS), "inventory:adjust"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.SALES), "inventory:adjust"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.ACCOUNTANT), "inventory:adjust"));
        // 库存盘点（M3-T03）：ADMIN/BOSS/WAREHOUSE 持有，其余角色拒绝
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.WAREHOUSE), "inventory:count"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.BOSS), "inventory:count"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.ADMIN), "inventory:count"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.SALES), "inventory:count"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.ACCOUNTANT), "inventory:count"));
        // 库存调拨（M3-T04）：ADMIN/BOSS/WAREHOUSE 持有，其余角色拒绝
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.WAREHOUSE), "inventory:transfer"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.BOSS), "inventory:transfer"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.ADMIN), "inventory:transfer"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.SALES), "inventory:transfer"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.ACCOUNTANT), "inventory:transfer"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.WAREHOUSE), "partner:create_customer"));

        // 采购订单（M3-T05）：ADMIN/BOSS/PURCHASER 持有，其余角色拒绝
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.PURCHASER), "purchase:order"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.BOSS), "purchase:order"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.ADMIN), "purchase:order"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.WAREHOUSE), "purchase:order"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.ACCOUNTANT), "purchase:order"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.SALES), "purchase:order"));
        // 采购入库（M3-T06）：ADMIN/BOSS/PURCHASER/WAREHOUSE 持有，其余角色拒绝
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.PURCHASER), "purchase:receipt"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.WAREHOUSE), "purchase:receipt"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.BOSS), "purchase:receipt"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.ADMIN), "purchase:receipt"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.ACCOUNTANT), "purchase:receipt"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.SALES), "purchase:receipt"));
        // 采购发票（M3-T07）：ADMIN/BOSS/PURCHASER/ACCOUNTANT 持有，其余角色拒绝
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.PURCHASER), "purchase:invoice"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.ACCOUNTANT), "purchase:invoice"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.BOSS), "purchase:invoice"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.ADMIN), "purchase:invoice"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.WAREHOUSE), "purchase:invoice"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.SALES), "purchase:invoice"));

        // 销售订单（M3-T08）：ADMIN/BOSS/SALES 持有，其余角色拒绝
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.SALES), "sales:order"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.BOSS), "sales:order"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.ADMIN), "sales:order"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.WAREHOUSE), "sales:order"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.ACCOUNTANT), "sales:order"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.PURCHASER), "sales:order"));
        // 销售出库（M3-T09）：ADMIN/BOSS/SALES/WAREHOUSE 持有，其余角色拒绝
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.SALES), "sales:delivery"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.WAREHOUSE), "sales:delivery"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.BOSS), "sales:delivery"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.ADMIN), "sales:delivery"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.ACCOUNTANT), "sales:delivery"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.PURCHASER), "sales:delivery"));
        // 销售发票与应收（M3-T10）：ADMIN/BOSS/SALES/ACCOUNTANT 持有，其余角色拒绝
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.SALES), "sales:invoice"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.ACCOUNTANT), "sales:invoice"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.BOSS), "sales:invoice"));
        assertTrue(RolePermissions.isGrantedCode(Set.of(Role.ADMIN), "sales:invoice"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.WAREHOUSE), "sales:invoice"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.PURCHASER), "sales:invoice"));
        // PURCHASER 不含任何 sales:*；SALES 不含任何 purchase:*（两线互不串权）
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.PURCHASER), "sales:order"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.SALES), "purchase:order"));
    }

    @Test
    void 未知权限点code一律拒绝_即使是管理员() {
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.ADMIN), "no:such_permission"));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.ADMIN), null));
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.ADMIN), "  "));
    }

    @Test
    void 权限点code解析_strip后匹配() {
        assertEquals(Permission.PARTNER_CREATE_CUSTOMER,
                Permission.fromCode(" partner:create_customer ").orElseThrow());
        assertTrue(Permission.fromCode("partner:CREATE_customer").isEmpty()); // 大小写敏感
    }
}
