package com.sjherp.domain.sales;

import java.math.BigDecimal;
import java.util.Objects;

import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 销售订单行项目（M3-T08）。
 *
 * <p>一行 = 一个商品的销售约定：数量 + 单价（销售价，由订单录入，价格策略从简，
 * 不走客户等级价 P2）。金额 = 数量 × 单价（2 位 HALF_UP）。
 *
 * <h2>累计发货量 deliveredQty</h2>
 * 销售出库单（M3-T09）按行部分发货，发货过账时回写本行的 {@link #deliveredQty 累计发货量}，
 * 用于校验「单行累计发货不得超过订单量」（剩余可发量 = 数量 − 累计发货量）。
 * 建单时累计发货量为 0。
 *
 * <p>数量一律基本单位（多单位换算在调用方完成，本模型不换算——与库存服务同口径，防双重换算）。
 * 金额/数量/单价一律 {@link BigDecimal}（CLAUDE.md 原则 5）。
 */
public final class SalesOrderLine {

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 行号（单据内从 1 起） */
    private final int lineNo;

    private final long productId;

    /** 订单数量（基本单位，6 位小数，> 0） */
    private final BigDecimal quantity;

    /** 销售单价（≥0，6 位小数，订单录入价） */
    private final BigDecimal unitPrice;

    /** 行金额 = 数量 × 单价（2 位 HALF_UP） */
    private final BigDecimal amount;

    /** 累计发货量（基本单位，6 位；建单为 0，发货过账时回写） */
    private BigDecimal deliveredQty;

    private SalesOrderLine(Long id, int lineNo, long productId, BigDecimal quantity,
                           BigDecimal unitPrice, BigDecimal amount, BigDecimal deliveredQty) {
        this.id = id;
        this.lineNo = lineNo;
        this.productId = productId;
        this.quantity = Objects.requireNonNull(quantity, "订单数量不能为空");
        this.unitPrice = Objects.requireNonNull(unitPrice, "销售单价不能为空");
        this.amount = Objects.requireNonNull(amount, "行金额不能为空");
        this.deliveredQty = Objects.requireNonNull(deliveredQty, "累计发货量不能为空");
    }

    /**
     * 建单工厂：行号、商品、数量、销售单价；金额自动按数量 × 单价（2 位 HALF_UP）算，累计发货量 0。
     *
     * @param lineNo    行号（>=1）
     * @param productId 商品 id
     * @param quantity  订单数量（基本单位，> 0，最多 6 位小数）
     * @param unitPrice 销售单价（>=0，最多 6 位小数）
     */
    public static SalesOrderLine create(int lineNo, long productId, BigDecimal quantity,
                                        BigDecimal unitPrice) {
        if (lineNo < 1) {
            throw new IllegalArgumentException("销售订单行号必须 >= 1: " + lineNo);
        }
        BigDecimal qty = validatedQuantity(quantity);
        BigDecimal price = validatedUnitPrice(unitPrice);
        BigDecimal amount = qty.multiply(price).setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
        return new SalesOrderLine(null, lineNo, productId, qty, price, amount,
                BigDecimal.ZERO.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING));
    }

    /** 持久层重建工厂（不重跑业务校验，含累计发货量） */
    public static SalesOrderLine restore(long id, int lineNo, long productId, BigDecimal quantity,
                                         BigDecimal unitPrice, BigDecimal amount, BigDecimal deliveredQty) {
        return new SalesOrderLine(id, lineNo, productId, quantity, unitPrice, amount, deliveredQty);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("销售订单行 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    /** 剩余可发量 = 订单数量 − 累计发货量（>= 0；出库单按此校验单行不超发） */
    public BigDecimal remainingQty() {
        return quantity.subtract(deliveredQty);
    }

    /**
     * 累加本行发货量（发货过账时调用）：校验不超过剩余可发量，否则拒绝（保证累计发货 ≤ 订单量）。
     *
     * @param deliveredDelta 本次发货量（基本单位，> 0）
     */
    public void addDelivered(BigDecimal deliveredDelta) {
        Objects.requireNonNull(deliveredDelta, "本次发货量不能为空");
        if (deliveredDelta.signum() <= 0) {
            throw new IllegalArgumentException("本次发货量必须大于 0: " + deliveredDelta.toPlainString());
        }
        BigDecimal remaining = remainingQty();
        if (deliveredDelta.compareTo(remaining) > 0) {
            throw new IllegalArgumentException("行号 " + lineNo + "（商品 " + productId
                    + "）本次发货 " + deliveredDelta.toPlainString() + " 超过剩余可发量 "
                    + remaining.toPlainString() + "（订单量 " + quantity.toPlainString()
                    + " − 已发 " + deliveredQty.toPlainString() + "）");
        }
        this.deliveredQty = this.deliveredQty.add(deliveredDelta);
    }

    /**
     * 回滚本行发货量（M4-T07b 销售出库红冲时由 {@link SalesOrder} 编排回写）。
     * delta &gt; 0 校验；回滚后累计发货量不得 &lt; 0（守门，防回滚多于已发虚减——保模型不破碎）。
     *
     * @param delta 本次回滚的发货量（&gt; 0，基本单位）
     */
    public void subtractDelivered(BigDecimal delta) {
        Objects.requireNonNull(delta, "本次回滚发货量不能为空");
        if (delta.signum() <= 0) {
            throw new IllegalArgumentException("本次回滚发货量必须大于 0: " + delta.toPlainString());
        }
        BigDecimal next = deliveredQty.subtract(delta);
        if (next.signum() < 0) {
            throw new IllegalArgumentException("行号 " + lineNo + "（商品 " + productId
                    + "）回滚发货量 " + delta.toPlainString() + " 超过累计发货量 " + deliveredQty.toPlainString());
        }
        this.deliveredQty = next;
    }

    private static BigDecimal validatedQuantity(BigDecimal quantity) {
        if (quantity == null) {
            throw new IllegalArgumentException("订单数量不能为空");
        }
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("订单数量必须大于 0: " + quantity.toPlainString());
        }
        if (quantity.stripTrailingZeros().scale() > CostingStrategy.UNIT_COST_SCALE) {
            throw new IllegalArgumentException("订单数量最多 " + CostingStrategy.UNIT_COST_SCALE
                    + " 位小数（基本单位记账）: " + quantity.toPlainString());
        }
        return quantity.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING);
    }

    private static BigDecimal validatedUnitPrice(BigDecimal unitPrice) {
        if (unitPrice == null) {
            throw new IllegalArgumentException("销售单价不能为空");
        }
        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("销售单价不能为负: " + unitPrice.toPlainString());
        }
        if (unitPrice.stripTrailingZeros().scale() > CostingStrategy.UNIT_COST_SCALE) {
            throw new IllegalArgumentException("销售单价最多 " + CostingStrategy.UNIT_COST_SCALE
                    + " 位小数: " + unitPrice.toPlainString());
        }
        return unitPrice.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING);
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

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getDeliveredQty() {
        return deliveredQty;
    }
}
