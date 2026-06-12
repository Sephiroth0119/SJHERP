package com.sjherp.domain.inventory;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 库存不足领域异常（拆解 §1.5）：默认禁止负库存，出库后数量将为负时抛出，
 * 整个事务回滚。携带仓库/商品/现存量/需求量四要素，文案 Agent 可读。
 */
public class InsufficientStockException extends RuntimeException {

    private final long warehouseId;
    private final long productId;

    /** 出库前现存数量（基本单位） */
    private final BigDecimal available;

    /** 本次需求数量（基本单位，正数） */
    private final BigDecimal requested;

    public InsufficientStockException(long warehouseId, long productId,
                                      BigDecimal available, BigDecimal requested) {
        this("库存不足：仓库[" + warehouseId + "] 商品[" + productId + "] 现存数量 "
                        + available.toPlainString() + "，需求数量 " + requested.toPlainString(),
                warehouseId, productId, available, requested);
    }

    private InsufficientStockException(String message, long warehouseId, long productId,
                                       BigDecimal available, BigDecimal requested) {
        super(message);
        this.warehouseId = warehouseId;
        this.productId = productId;
        this.available = Objects.requireNonNull(available, "available 不能为空");
        this.requested = Objects.requireNonNull(requested, "requested 不能为空");
    }

    /**
     * 负库存放行但无法定价：出库前数量 ≤ 0 且该仓该商品没有任何带单价的历史流水，
     * 加权单价与退化口径（最近一笔流水单价）都无从谈起，仍拒绝（拆解 §1.5）。
     */
    public static InsufficientStockException noCostBasis(long warehouseId, long productId,
                                                         BigDecimal available, BigDecimal requested) {
        return new InsufficientStockException(
                "无法确定出库成本：仓库[" + warehouseId + "] 商品[" + productId
                        + "] 无任何带单价的历史流水（现存数量 " + available.toPlainString()
                        + "，需求数量 " + requested.toPlainString() + "），拒绝出库",
                warehouseId, productId, available, requested);
    }

    public long getWarehouseId() {
        return warehouseId;
    }

    public long getProductId() {
        return productId;
    }

    public BigDecimal getAvailable() {
        return available;
    }

    public BigDecimal getRequested() {
        return requested;
    }
}
