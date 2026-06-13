package com.sjherp.domain.sales;

import java.math.BigDecimal;
import java.util.Objects;

import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 销售发票行项目（M3-T10，路线图 §5 销售线）。
 *
 * <p>一行 = 对出库单某行已发货商品的开票：开票数量 + 开票单价（价格策略从简，用发票录入价，
 * 客户等级价 P2 跳过）。金额 = 数量 × 单价（2 位 HALF_UP）。
 *
 * <p>开票数量不得超过对应出库行的已发货数量（校验在发票服务）。数量/金额一律
 * {@link BigDecimal}（CLAUDE.md 原则 5）。
 */
public final class SalesInvoiceLine {

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 发票行号（单据内从 1 起） */
    private final int lineNo;

    /** 关联的出库单行号（本行开票针对出库的哪一行） */
    private final int deliveryLineNo;

    private final long productId;

    /** 开票数量（基本单位，6 位，> 0；≤ 出库行已发量） */
    private final BigDecimal quantity;

    /** 开票单价（>=0，6 位，发票录入价） */
    private final BigDecimal unitPrice;

    /** 行金额 = 数量 × 单价（2 位 HALF_UP） */
    private final BigDecimal amount;

    private SalesInvoiceLine(Long id, int lineNo, int deliveryLineNo, long productId,
                            BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
        this.id = id;
        this.lineNo = lineNo;
        this.deliveryLineNo = deliveryLineNo;
        this.productId = productId;
        this.quantity = Objects.requireNonNull(quantity, "开票数量不能为空");
        this.unitPrice = Objects.requireNonNull(unitPrice, "开票单价不能为空");
        this.amount = Objects.requireNonNull(amount, "行金额不能为空");
    }

    /**
     * 建单工厂：发票行号、关联出库行号、商品、开票数量、单价；金额自动算（2 位 HALF_UP）。
     *
     * @param lineNo         发票行号（>=1）
     * @param deliveryLineNo 关联出库单行号（>=1）
     * @param productId      商品 id
     * @param quantity       开票数量（基本单位，> 0，最多 6 位小数）
     * @param unitPrice      开票单价（>=0，最多 6 位小数）
     */
    public static SalesInvoiceLine create(int lineNo, int deliveryLineNo, long productId,
                                          BigDecimal quantity, BigDecimal unitPrice) {
        if (lineNo < 1) {
            throw new IllegalArgumentException("发票行号必须 >= 1: " + lineNo);
        }
        if (deliveryLineNo < 1) {
            throw new IllegalArgumentException("关联出库行号必须 >= 1: " + deliveryLineNo);
        }
        BigDecimal qty = validatedQuantity(quantity);
        BigDecimal price = validatedUnitPrice(unitPrice);
        BigDecimal amount = qty.multiply(price).setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
        return new SalesInvoiceLine(null, lineNo, deliveryLineNo, productId, qty, price, amount);
    }

    /** 持久层重建工厂（不重跑业务校验） */
    public static SalesInvoiceLine restore(long id, int lineNo, int deliveryLineNo, long productId,
                                           BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
        return new SalesInvoiceLine(id, lineNo, deliveryLineNo, productId, quantity, unitPrice, amount);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("发票行 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    private static BigDecimal validatedQuantity(BigDecimal quantity) {
        if (quantity == null) {
            throw new IllegalArgumentException("开票数量不能为空");
        }
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("开票数量必须大于 0: " + quantity.toPlainString());
        }
        if (quantity.stripTrailingZeros().scale() > CostingStrategy.UNIT_COST_SCALE) {
            throw new IllegalArgumentException("开票数量最多 " + CostingStrategy.UNIT_COST_SCALE
                    + " 位小数（基本单位记账）: " + quantity.toPlainString());
        }
        return quantity.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING);
    }

    private static BigDecimal validatedUnitPrice(BigDecimal unitPrice) {
        if (unitPrice == null) {
            throw new IllegalArgumentException("开票单价不能为空");
        }
        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("开票单价不能为负: " + unitPrice.toPlainString());
        }
        if (unitPrice.stripTrailingZeros().scale() > CostingStrategy.UNIT_COST_SCALE) {
            throw new IllegalArgumentException("开票单价最多 " + CostingStrategy.UNIT_COST_SCALE
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

    public int getDeliveryLineNo() {
        return deliveryLineNo;
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
}
