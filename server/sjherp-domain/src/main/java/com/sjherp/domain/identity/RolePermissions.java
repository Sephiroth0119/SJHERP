package com.sjherp.domain.identity;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
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
 *   <li>PURCHASER：创建供应商（采购线档案）+ 采购全程（采购订单/入库/发票）；
 *       商品/档案查询登录即可，无需权限点；</li>
 *   <li>SALES：创建客户（销售线档案）+ 销售全线（销售订单/出库/发票应收）；</li>
 *   <li>WAREHOUSE：仓库域全部（创建 + 维护 + 库存调整 + 库存盘点 + 库存调拨）
 *       + 采购收货（purchase:receipt）+ 销售发货（sales:delivery）；</li>
 *   <li>ACCOUNTANT：采购发票/应付（purchase:invoice）+ 销售发票/应收（sales:invoice）
 *       + 总账财务（finance:account/period/voucher，M4-T01）+ 核销与账龄（finance:settlement，M4-T03）
 *       + 资金账户档案（finance:payment_account，M4-T04a）；
 *       不含账期重开（finance:period_reopen 高敏，仅 ADMIN/BOSS）；其余 finance:* 留后续任务补充。</li>
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
        // M5-T01 新增：PRODUCTION_BOM / PRODUCTION_ROUTING（生产主数据管理）
        // M5-T02 新增：PRODUCTION_PLAN / PRODUCTION_MRP（需求计划 + MRP 运行）
        // M5-T03 新增：PRODUCTION_WO（生产工单）
        // M5-T04 新增：PRODUCTION_MATERIAL（生产领料与退料）
        grants.put(Role.BOSS, Collections.unmodifiableSet(EnumSet.of(
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
                Permission.FINANCE_ACCOUNT,
                Permission.FINANCE_PERIOD,
                Permission.FINANCE_PERIOD_REOPEN,
                Permission.FINANCE_VOUCHER,
                Permission.FINANCE_SETTLEMENT,
                Permission.FINANCE_PAYMENT_ACCOUNT,
                Permission.FINANCE_REPORT,
                Permission.PRODUCTION_BOM,
                Permission.PRODUCTION_ROUTING,
                Permission.PRODUCTION_PLAN,
                Permission.PRODUCTION_MRP,
                Permission.PRODUCTION_WO,
                Permission.PRODUCTION_MATERIAL,
                Permission.PRODUCTION_REPORT,
                Permission.PRODUCTION_COST,
                Permission.MEMORY_MANAGE,
                Permission.DATA_IMPORT,
                Permission.GAP_TRIAGE,
                Permission.GAP_ISSUE,
                Permission.DEVELOPER_AGENT)));

        // PURCHASER：创建供应商 + 采购全程参与（采购订单/入库/发票）；商品/供应商查询登录即可
        grants.put(Role.PURCHASER, Collections.unmodifiableSet(EnumSet.of(
                Permission.PARTNER_CREATE_SUPPLIER,
                Permission.PURCHASE_ORDER,
                Permission.PURCHASE_RECEIPT,
                Permission.PURCHASE_INVOICE)));

        // SALES：创建客户 + 销售全线（销售订单/出库/发票应收）
        grants.put(Role.SALES, Collections.unmodifiableSet(EnumSet.of(
                Permission.PARTNER_CREATE_CUSTOMER,
                Permission.SALES_ORDER,
                Permission.SALES_DELIVERY,
                Permission.SALES_INVOICE)));

        // WAREHOUSE：仓库域全部（创建 + 维护 + 库存调整 + 库存盘点 + 库存调拨）
        // + 采购收货（purchase:receipt）+ 销售发货（sales:delivery）——仓管负责实物出入库
        grants.put(Role.WAREHOUSE, Collections.unmodifiableSet(EnumSet.of(
                Permission.WAREHOUSE_CREATE_WAREHOUSE,
                Permission.WAREHOUSE_WRITE,
                Permission.INVENTORY_ADJUST,
                Permission.INVENTORY_COUNT,
                Permission.INVENTORY_TRANSFER,
                Permission.PURCHASE_RECEIPT,
                Permission.SALES_DELIVERY)));

        // ACCOUNTANT：采购发票/应付 + 销售发票/应收 + 总账财务（科目/账期/凭证）+ 核销与账龄（M4-T03）
        // + 资金账户（M4-T04a）+ 会计报表（finance:report，M4-T06 资产负债表/利润表）
        // + 生产成本归集与结转（production:cost，M5-T06，成本结转是会计动作 D8）；
        // 不含 finance:period_reopen（账期重开高敏，仅 ADMIN/BOSS）。其余 finance:* 留后续任务补充
        grants.put(Role.ACCOUNTANT, Collections.unmodifiableSet(EnumSet.of(
                Permission.PURCHASE_INVOICE,
                Permission.SALES_INVOICE,
                Permission.FINANCE_ACCOUNT,
                Permission.FINANCE_PERIOD,
                Permission.FINANCE_VOUCHER,
                Permission.FINANCE_SETTLEMENT,
                Permission.FINANCE_PAYMENT_ACCOUNT,
                Permission.FINANCE_REPORT,
                Permission.PRODUCTION_COST)));

        return Collections.unmodifiableMap(grants);
    }

    /** 单个角色的权限点集合（不可变；所有角色都有映射项，返回值绝不为 null） */
    public static Set<Permission> permissionsOf(Role role) {
        return GRANTS.get(role);
    }

    /** 多角色的有效权限码并集，作为认证 API 向前端导出的唯一权限真源。 */
    public static List<String> permissionCodesOf(Set<Role> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        EnumSet<Permission> permissions = EnumSet.noneOf(Permission.class);
        roles.stream()
                .filter(role -> role != null)
                .map(GRANTS::get)
                .filter(grant -> grant != null)
                .forEach(permissions::addAll);
        return permissions.stream().map(Permission::code).sorted().toList();
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
