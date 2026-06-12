package com.sjherp.domain.identity;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 功能权限点（M2-T06 权限模型）。
 *
 * <p>权限点是"角色能做什么"的最小授权单元，code 为稳定字符串标识，
 * 与两处声明保持一致：
 * <ul>
 *   <li>Agent 工具的 {@code Tool#requiredPermission()}（如 partner:create_customer）；</li>
 *   <li>REST 写接口的 {@code @PreAuthorize("@perm.has('...')")} 表达式。</li>
 * </ul>
 *
 * <p>约定：<b>查询类操作不设权限点</b>（登录即可）；写操作分两层粒度——
 * 与 Agent 工具一一对应的单项创建权限点，以及各域一个的 REST 档案写权限点
 * （更新/启停等维护操作）。角色到权限点的静态映射见 {@link RolePermissions}，
 * 完整矩阵文档见 docs/权限矩阵.md。
 */
public enum Permission {

    // ------------------------------------------------- 单项创建（与 Agent 工具声明一致）

    /** 创建商品（Agent 工具 create_product / REST POST /api/catalog/products） */
    CATALOG_CREATE_PRODUCT("catalog:create_product", "创建商品"),

    /** 创建客户（Agent 工具 create_customer / REST POST /api/partner/customers） */
    PARTNER_CREATE_CUSTOMER("partner:create_customer", "创建客户"),

    /** 创建供应商（Agent 工具 create_supplier / REST POST /api/partner/suppliers） */
    PARTNER_CREATE_SUPPLIER("partner:create_supplier", "创建供应商"),

    /** 创建仓库（Agent 工具 create_warehouse / REST POST /api/warehouse/warehouses） */
    WAREHOUSE_CREATE_WAREHOUSE("warehouse:create_warehouse", "创建仓库"),

    // ------------------------------------------------- REST 档案维护（各域一个，更新/启停/类目/单位）

    /** 商品域档案维护：商品更新/启停、类目与计量单位的全部写操作 */
    CATALOG_WRITE("catalog:write", "商品档案维护"),

    /** 往来域档案维护：客户/供应商的更新与启停 */
    PARTNER_WRITE("partner:write", "往来档案维护"),

    /** 仓库域档案维护：仓库的更新与启停 */
    WAREHOUSE_WRITE("warehouse:write", "仓库档案维护"),

    // ------------------------------------------------- 库存（M3-T01c）

    /**
     * 库存调整：期初建账（OPENING）与成本调整（COST_ADJUST）的写入口
     * （Agent 工具 adjust_inventory / REST POST /api/inventory/adjustments）。
     * 库存余额/流水查询照例不设权限点（登录即可）。
     */
    INVENTORY_ADJUST("inventory:adjust", "库存调整"),

    // ------------------------------------------------- 流程缺口

    /** 缺口状态流转（GapController POST /api/gaps/{id}/status，开发侧操作） */
    GAP_TRIAGE("gap:triage", "缺口状态流转"),

    // ------------------------------------------------- 演示（仅 dev/local profile 注册的演示工具）

    /** 演示用高风险工具 demo_post_document（dev/local 验证 HITL 链路；生产不注册该工具） */
    DEMO_POST_DOCUMENT("demo:post_document", "演示高风险操作");

    // 财务权限点（finance:*，如过账/结账/付款）留 M4 财务模块落地时补充——
    // 届时 ACCOUNTANT 角色在 RolePermissions 的空集合一并填充。

    /** 稳定字符串标识（与 Tool.requiredPermission / @PreAuthorize 表达式一致，不可改） */
    private final String code;

    /** 用户可见的中文名称 */
    private final String displayName;

    private static final Map<String, Permission> BY_CODE = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(Permission::code, Function.identity()));

    Permission(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    /** 按 code 解析；未知 code 返回空（调用方应视为"无此权限"，宁拒勿放） */
    public static Optional<Permission> fromCode(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(BY_CODE.get(code.strip()));
    }
}
