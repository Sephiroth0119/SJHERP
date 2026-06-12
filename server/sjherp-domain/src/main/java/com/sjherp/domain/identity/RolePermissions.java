package com.sjherp.domain.identity;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 角色 → 权限点的静态映射（M2-T06，小企业从简：代码内定义，不落库、不可配置）。
 *
 * <p>映射原则（完整矩阵见 docs/权限矩阵.md，两处必须同步维护）：
 * <ul>
 *   <li>ADMIN：全部权限点（含 dev/local 演示工具）；用户管理另由角色直接限定
 *       （UserAdminController @PreAuthorize("hasRole('ADMIN')")），不走权限点；</li>
 *   <li>BOSS：全部业务权限点（不含用户管理，不含演示工具）；</li>
 *   <li>PURCHASER：创建供应商（采购线档案）；商品/档案查询登录即可，无需权限点；</li>
 *   <li>SALES：创建客户（销售线档案）；</li>
 *   <li>WAREHOUSE：仓库域全部（创建 + 维护）；</li>
 *   <li>ACCOUNTANT：读为主，本期无写权限点——财务权限点（finance:* 过账/结账/付款等）
 *       留 M4 财务模块落地时补充。</li>
 * </ul>
 *
 * <p>多角色用户取并集；未知权限点 code 一律判拒（宁拒勿放，避免拼写错误变成放行）。
 */
public final class RolePermissions {

    /** 角色 → 权限点集合（不可变视图，初始化后只读） */
    private static final Map<Role, Set<Permission>> GRANTS = buildGrants();

    private RolePermissions() {
    }

    private static Map<Role, Set<Permission>> buildGrants() {
        EnumMap<Role, Set<Permission>> grants = new EnumMap<>(Role.class);

        // ADMIN：全部权限点（含演示工具——演示工具仅 dev/local profile 注册，生产不可达）
        grants.put(Role.ADMIN, Collections.unmodifiableSet(EnumSet.allOf(Permission.class)));

        // BOSS：全部业务权限点（不含用户管理——非权限点；不含演示工具）
        grants.put(Role.BOSS, Collections.unmodifiableSet(EnumSet.of(
                Permission.CATALOG_CREATE_PRODUCT,
                Permission.PARTNER_CREATE_CUSTOMER,
                Permission.PARTNER_CREATE_SUPPLIER,
                Permission.WAREHOUSE_CREATE_WAREHOUSE,
                Permission.CATALOG_WRITE,
                Permission.PARTNER_WRITE,
                Permission.WAREHOUSE_WRITE,
                Permission.INVENTORY_ADJUST,
                Permission.GAP_TRIAGE)));

        // PURCHASER：采购线只开供应商创建（商品/供应商查询登录即可）
        grants.put(Role.PURCHASER, Collections.unmodifiableSet(EnumSet.of(
                Permission.PARTNER_CREATE_SUPPLIER)));

        // SALES：销售线只开客户创建
        grants.put(Role.SALES, Collections.unmodifiableSet(EnumSet.of(
                Permission.PARTNER_CREATE_CUSTOMER)));

        // WAREHOUSE：仓库域全部（创建 + 维护 + 库存调整），即 warehouse:* + inventory:adjust
        grants.put(Role.WAREHOUSE, Collections.unmodifiableSet(EnumSet.of(
                Permission.WAREHOUSE_CREATE_WAREHOUSE,
                Permission.WAREHOUSE_WRITE,
                Permission.INVENTORY_ADJUST)));

        // ACCOUNTANT：读为主，本期无写权限点；finance:* 权限点留 M4 在此补充
        grants.put(Role.ACCOUNTANT, Collections.unmodifiableSet(EnumSet.noneOf(Permission.class)));

        return Collections.unmodifiableMap(grants);
    }

    /** 单个角色的权限点集合（不可变；所有角色都有映射项，返回值绝不为 null） */
    public static Set<Permission> permissionsOf(Role role) {
        return GRANTS.get(role);
    }

    /** 角色集合是否被授予某权限点（多角色取并集；roles 为空一律拒绝） */
    public static boolean isGranted(Set<Role> roles, Permission permission) {
        if (roles == null || roles.isEmpty() || permission == null) {
            return false;
        }
        return roles.stream().anyMatch(role -> GRANTS.get(role).contains(permission));
    }

    /**
     * 按权限点 code 校验（工具层与 @PreAuthorize 表达式的入口）。
     * 未知 code 一律返回 false——宁可误拒一个拼写错误的权限点，不可放行。
     */
    public static boolean isGrantedCode(Set<Role> roles, String permissionCode) {
        return Permission.fromCode(permissionCode)
                .map(permission -> isGranted(roles, permission))
                .orElse(false);
    }
}
