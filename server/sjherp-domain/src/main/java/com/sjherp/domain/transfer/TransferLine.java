package com.sjherp.domain.transfer;

import java.math.BigDecimal;
import java.util.Objects;

import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 调拨单行项目（M3-T04，拆解 docs/M3拆解-库存与成本.md §1.6.5）。
 *
 * <p>一行 = 一个商品从调出仓到调入仓的转移数量。调拨单头固定调出仓/调入仓，
 * 行只承载商品与调拨数量；过账时每行拆成「调出腿（TRANSFER_OUT）+ 调入腿（TRANSFER_IN）」
 * 两笔同事务原子的库存流水（成本守恒由库存服务保证，见 {@link TransferService}）。
 *
 * <p>数量一律基本单位（多单位换算在调用方完成，本模型不换算——与库存服务同口径，防双重换算）。
 */
public final class TransferLine {

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 行号（单据内从 1 起，幂等键 TRANSFER:docNo:行号:OUT/:IN 用此） */
    private final int lineNo;

    private final long productId;

    /** 调拨数量（基本单位，6 位小数，> 0） */
    private final BigDecimal quantity;

    private TransferLine(Long id, int lineNo, long productId, BigDecimal quantity) {
        this.id = id;
        this.lineNo = lineNo;
        this.productId = productId;
        this.quantity = Objects.requireNonNull(quantity, "调拨数量不能为空");
    }

    /**
     * 建单工厂：行号、商品、调拨数量。
     *
     * @param lineNo    行号（>=1）
     * @param productId 商品 id
     * @param quantity  调拨数量（基本单位，> 0，最多 6 位小数）
     */
    public static TransferLine create(int lineNo, long productId, BigDecimal quantity) {
        if (lineNo < 1) {
            throw new IllegalArgumentException("调拨单行号必须 >= 1: " + lineNo);
        }
        validateQuantity(quantity);
        return new TransferLine(null, lineNo, productId,
                quantity.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING));
    }

    /** 持久层重建工厂（不重跑业务校验） */
    public static TransferLine restore(long id, int lineNo, long productId, BigDecimal quantity) {
        return new TransferLine(id, lineNo, productId, quantity);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("调拨单行 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    private static void validateQuantity(BigDecimal quantity) {
        if (quantity == null) {
            throw new IllegalArgumentException("调拨数量不能为空");
        }
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("调拨数量必须大于 0: " + quantity.toPlainString());
        }
        if (quantity.stripTrailingZeros().scale() > CostingStrategy.UNIT_COST_SCALE) {
            throw new IllegalArgumentException("调拨数量最多 " + CostingStrategy.UNIT_COST_SCALE
                    + " 位小数（基本单位记账）: " + quantity.toPlainString());
        }
    }

    public Long getId() {
        return id;
    }

    public int getLineNo() {
        return lineNo;
    }

    public long getProductId() {
        return productId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }
}
