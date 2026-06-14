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

    /**
     * 库存盘点：盘点单建单/录入实盘/审核/过账/查询的受控动作权限点
     * （Agent 工具 create_stock_count / query_stock_count；REST /api/inventory/stock-counts**，M3-T03）。
     * 注意：盘点单查询接口也要求本权限点（StocktakeController 类级 @PreAuthorize），
     * 区别于「查询登录即可」的通则——因为盘点是受控动作。
     */
    INVENTORY_COUNT("inventory:count", "库存盘点"),

    /**
     * 库存调拨：调拨单建单/审核/过账的写入口
     * （Agent 工具 create_transfer / REST /api/inventory/transfers**，M3-T04）。
     * 注意：调拨单查询接口也要求本权限点（TransferController 类级 @PreAuthorize），
     * 区别于「查询登录即可」的通则——因为调拨是受控动作。
     */
    INVENTORY_TRANSFER("inventory:transfer", "库存调拨"),

    // ------------------------------------------------- 采购线（M3-T05/T06/T07）

    /**
     * 采购订单：下单/审核/关闭/查询的受控动作权限点
     * （Agent 工具 create_purchase_order / query_purchase_order；REST /api/purchase/orders**，M3-T05）。
     * 注意：采购订单查询接口也要求本权限点（PurchaseOrderController 类级 @PreAuthorize），
     * 区别于「查询登录即可」通则——采购订单是受控动作。
     */
    PURCHASE_ORDER("purchase:order", "采购订单"),

    /**
     * 采购入库单：收货/审核/过账/查询的写入口
     * （REST /api/purchase/receipts**，M3-T06；收货工具留 M3-T11）。查询接口同样要求本权限点。
     */
    PURCHASE_RECEIPT("purchase:receipt", "采购入库"),

    /**
     * 采购发票：登记/审核/过账/查询的写入口
     * （REST /api/purchase/invoices**，M3-T07；发票工具留 M3-T11）。查询接口同样要求本权限点。
     * 应付列表查询 GET /api/payables 不设权限点（登录即可，只读台账）。
     */
    PURCHASE_INVOICE("purchase:invoice", "采购发票"),

    // ------------------------------------------------- 销售线（M3-T08/T09/T10）

    /**
     * 销售订单：建单/审核/作废/查询（Agent 工具 create_sales_order / query_sales_order；
     * REST /api/sales/orders**，M3-T08）。注意：订单查询接口也要求本权限点（SalesOrderController
     * 类级 @PreAuthorize），区别于「查询登录即可」的通则——销售订单整体属于受控动作。
     */
    SALES_ORDER("sales:order", "销售订单"),

    /**
     * 销售出库：建单/审核/过账/作废/查询（REST /api/sales/deliveries**，M3-T09）。
     * 过账经库存唯一写入口产生 SALES_OUT 流水并结转 COGS。查询接口同样要求本权限点。
     */
    SALES_DELIVERY("sales:delivery", "销售出库"),

    /**
     * 销售发票与应收：发票建单/审核/过账/作废/查询、应收查询（REST /api/sales/invoices**、
     * GET /api/receivables**，M3-T10）。过账按发票金额生成应收账款（OPEN，核销 M4-T03）。
     * 应收是开票的财务产出，与发票同权（受控查询）。
     */
    SALES_INVOICE("sales:invoice", "销售发票与应收"),

    // ------------------------------------------------- 数据导入（M2-T09）

    /**
     * Excel 期初数据导入：基础档案（商品/客户/供应商）+ 期初库存建账。
     * REST 端点 POST /api/import/**（非 Agent 工具，导入为建账期批量管理动作）。
     * 模板下载 GET /api/import/templates/{type} 不设权限点（登录即可）。
     * 授 ADMIN/BOSS（建账期一次性管理动作）；期初库存写入口唯一经 InventoryAdjustmentService.opening。
     */
    DATA_IMPORT("data:import", "数据导入"),

    // ------------------------------------------------- 财务总账（M4-T01）

    /**
     * 会计科目维护：科目建档/启停（REST POST /api/gl/accounts、/accounts/{code}/disable|enable）。
     * 科目表/末级科目查询照例不设权限点（登录即可）。
     */
    FINANCE_ACCOUNT("finance:account", "会计科目维护"),

    /**
     * 会计期间管理：账期开启/关闭（REST POST /api/gl/periods、/periods/{period}/close）。
     * 账期列表/详情查询登录即可。重开账期单列高敏权限 {@link #FINANCE_PERIOD_REOPEN}。
     */
    FINANCE_PERIOD("finance:period", "会计期间管理"),

    /**
     * 会计期间重开（高敏，CLAUDE.md 原则 2：期间不可随意重开）：REST POST /api/gl/periods/{period}/reopen。
     * 仅授 ADMIN/BOSS（不授 ACCOUNTANT），与日常开关账分离把控。
     */
    FINANCE_PERIOD_REOPEN("finance:period_reopen", "会计期间重开"),

    /**
     * 凭证管理：凭证建单/过账/查询（REST /api/gl/vouchers**、/trial-balance、/account-balance，M4-T01）。
     * 凭证整体属受控动作，查询接口同样要求本权限点；红字冲销实现留 M4-T07。
     */
    FINANCE_VOUCHER("finance:voucher", "凭证管理"),

    /**
     * 应收应付核销与账龄（M4-T03）：本批仅护<b>只读</b>端点——账龄
     * （GET /api/reports/receivable-aging、/payable-aging，暴露对手方余额+逾期，敏感）与核销历史
     * （GET /api/settlements）。核销<b>写</b>动作（settle）的 REST/Agent 入口在 M4-T04（收付款单驱动），
     * 届时复用本权限点。授 ADMIN/BOSS/ACCOUNTANT（与 finance:voucher 同档，核销是会计日常）。
     */
    FINANCE_SETTLEMENT("finance:settlement", "应收应付核销与账龄"),

    /**
     * 资金账户档案（M4-T04a）：现金/银行账户 master 的<b>写</b>操作——建档/更新/启停
     * （Agent 工具 create_payment_account / REST POST·PUT /api/fund/accounts、/{id}/enable|disable）。
     * 资金账户查询（search_payment_accounts、GET /api/fund/accounts）照例不设权限点（登录即可）。
     * 授 ADMIN/BOSS/ACCOUNTANT（资金账户是会计基础档案）。
     */
    FINANCE_PAYMENT_ACCOUNT("finance:payment_account", "资金账户档案"),

    /**
     * 会计报表（M4-T06）：资产负债表 / 利润表的<b>只读</b>查询
     * （GET /api/reports/balance-sheet、/income-statement）。两报表暴露全盘财务状况
     * （资产负债结构、收入成本利润，<b>高度敏感</b>），故须本权限点（区别于"查询登录即可"通则）。
     * 授 ADMIN（allOf 自动）/BOSS/ACCOUNTANT（与 finance:voucher 同档，报表是会计日常产出）。
     * 财务 Agent 工具（查利润/资产负债，T08）届时复用本权限点。
     */
    FINANCE_REPORT("finance:report", "会计报表"),

    // ------------------------------------------------- 生产（M5-T01）

    /**
     * BOM 物料清单维护（M5-T01）：BOM 建档/更新/启停
     * （REST POST·PUT /api/production/boms、/{id}/enable|disable）。
     * BOM 查询（GET /api/production/boms、/{productId}/explode）照例不设权限点（登录即可）。
     * 授 ADMIN/BOSS（生产主数据管理，本批不引入 PRODUCTION 角色）。
     */
    PRODUCTION_BOM("production:bom", "BOM维护"),

    /**
     * 工艺路线维护（M5-T01）：工艺路线建档/更新/启停
     * （REST POST·PUT /api/production/routings、/{id}/enable|disable）。
     * 工艺路线查询（GET /api/production/routings）照例不设权限点（登录即可）。
     * 授 ADMIN/BOSS（生产主数据管理，本批不引入 PRODUCTION 角色）。
     */
    PRODUCTION_ROUTING("production:routing", "工艺路线维护"),

    /**
     * 需求计划维护（M5-T02）：需求计划建档/更新
     * （REST POST·PUT /api/production/demand-plans）。
     * 授 ADMIN/BOSS。
     */
    PRODUCTION_PLAN("production:plan", "需求计划"),

    /**
     * MRP 运行（M5-T02）：触发 MRP 运算/查询历史/查看建议
     * （REST POST /api/production/mrp/runs，GET 查询）。
     * 授 ADMIN/BOSS。
     */
    PRODUCTION_MRP("production:mrp", "MRP 运行"),

    // ------------------------------------------------- 流程缺口

    /** 缺口状态流转（GapController POST /api/gaps/{id}/status，开发侧操作） */
    GAP_TRIAGE("gap:triage", "缺口状态流转"),

    // ------------------------------------------------- 演示（仅 dev/local profile 注册的演示工具）

    /** 演示用高风险工具 demo_post_document（dev/local 验证 HITL 链路；生产不注册该工具） */
    DEMO_POST_DOCUMENT("demo:post_document", "演示高风险操作");

    // 总账财务权限点 finance:account/period/period_reopen/voucher 已于 M4-T01 落地（见上）；
    // 后续付款/收款核销（M4-T03）、关账结转（M4-T05）等财务权限点在对应任务落地时再补充。

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
