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
                Permission.GAP_TRIAGE), boss);
        assertFalse(boss.contains(Permission.DEMO_POST_DOCUMENT));
    }

    @Test
    void PURCHASER_仅创建供应商() {
        assertEquals(EnumSet.of(Permission.PARTNER_CREATE_SUPPLIER),
                RolePermissions.permissionsOf(Role.PURCHASER));
    }

    @Test
    void SALES_仅创建客户() {
        assertEquals(EnumSet.of(Permission.PARTNER_CREATE_CUSTOMER),
                RolePermissions.permissionsOf(Role.SALES));
    }

    @Test
    void WAREHOUSE_仓库域全部_无其他域() {
        assertEquals(EnumSet.of(Permission.WAREHOUSE_CREATE_WAREHOUSE, Permission.WAREHOUSE_WRITE),
                RolePermissions.permissionsOf(Role.WAREHOUSE));
    }

    @Test
    void ACCOUNTANT_本期无写权限点_财务权限留M4() {
        assertTrue(RolePermissions.permissionsOf(Role.ACCOUNTANT).isEmpty());
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
        assertFalse(RolePermissions.isGrantedCode(Set.of(Role.WAREHOUSE), "partner:create_customer"));
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
