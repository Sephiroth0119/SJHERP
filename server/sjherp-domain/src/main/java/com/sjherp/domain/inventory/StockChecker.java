package com.sjherp.domain.inventory;

/**
 * 库存占用检查端口（M3-T01c，补 M2 遗留 TODO：仓库/商品停用前的非零库存引用检查）。
 *
 * <p>设计取舍（最小侵入）：{@code WarehouseService} / {@code ProductService} 不直接依赖
 * {@link InventoryBalanceRepository}（该端口按 T01a 依赖清单只服务 {@link InventoryService}，
 * 且缺少"按仓库/按商品聚合"的查询，扩它会牵动 T01b 的实现契约），而是依赖本独立小端口；
 * 由 app 层以只读 SQL 装配（报表口径，CLAUDE.md「报表只读查询除外」）。
 *
 * <p>口径：余额行 quantity 或 cost_amount 任一非 0 即视为「存在库存占用」——
 * 数量出空但金额残留（理论上被出空清零规则排除）同样阻断，宁可拒绝，不可破坏模型。
 */
public interface StockChecker {

    /** 该仓库是否存在任何非零库存余额（任一商品的数量或金额非 0） */
    boolean warehouseHasStock(long warehouseId);

    /** 该商品是否在任一仓库存在非零库存余额 */
    boolean productHasStock(long productId);

    /**
     * 该商品是否曾产生库存流水。
     *
     * <p>存货类别一旦参与库存业务即不可修改，避免用当前主数据重解释历史业务和凭证科目。
     */
    default boolean productHasTransactions(long productId) {
        return false;
    }
}
