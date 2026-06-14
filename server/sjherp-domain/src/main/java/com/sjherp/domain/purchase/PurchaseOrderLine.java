package com.sjherp.domain.purchase;

import java.math.BigDecimal;
import java.util.Objects;

import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 采购订单行项目（M3-T05）。
 *
 * <p>一行 = 一个商品的采购数量与单价（含派生金额 = 数量 × 单价），并累计已到货数量
 * {@link #receivedQty}（采购入库单 M3-T06 过账时同事务回写，用于部分收货跟踪）。
 *
 * <p>下单不动库存（采购订单只是对供应商的采购承诺），库存只在采购入库单过账时产生。
 * 数量/单价/金额一律 {@link BigDecimal}（CLAUDE.md 原则 5），数量基本单位记账
 * （多单位换算在调用方完成，本模型不换算——与库存服务同口径，防双重换算）。
 */
public final class PurchaseOrderLine {

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 行号（单据内从 1 起，采购入库单引用行用此） */
    private final int lineNo;

    private final long productId;

    /** 订购数量（基本单位，6 位小数，> 0） */
    private final BigDecimal quantity;

    /** 采购单价（≥0，6 位小数） */
    private final BigDecimal unitPrice;

    /** 行金额（= 数量 × 单价，2 位小数，派生但落库便于汇总与审计） */
    private final BigDecimal amount;

    /** 累计已到货数量（基本单位，6 位小数，初始 0；采购入库过账时累加，永不超过 quantity） */
    private BigDecimal receivedQty;

    private PurchaseOrderLine(Long id, int lineNo, long productId, BigDecimal quantity,
                             BigDecimal unitPrice, BigDecimal amount, BigDecimal receivedQty) {
        this.id = id;
        this.lineNo = lineNo;
        this.productId = productId;
        this.quantity = Objects.requireNonNull(quantity, "订购数量不能为空");
        this.unitPrice = Objects.requireNonNull(unitPrice, "采购单价不能为空");
        this.amount = Objects.requireNonNull(amount, "行金额不能为空");
        this.receivedQty = Objects.requireNonNull(receivedQty, "已到货数量不能为空");
    }

    /**
     * 建单工厂：行号、商品、订购数量、采购单价（金额自动计算，已到货量初始 0）。
     *
     * @param lineNo    行号（>=1）
     * @param productId 商品 id
     * @param quantity  订购数量（基本单位，> 0，最多 6 位小数）
     * @param unitPrice 采购单价（≥0，最多 6 位小数）
     */
    public static PurchaseOrderLine create(int lineNo, long productId, BigDecimal quantity,
                                           BigDecimal unitPrice) {
        if (lineNo < 1) {
            throw new IllegalArgumentException("采购订单行号必须 >= 1: " + lineNo);
        }
        BigDecimal qty = normalizedQuantity(quantity);
        BigDecimal price = normalizedUnitPrice(unitPrice);
        BigDecimal amount = price.multiply(qty).setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
        return new PurchaseOrderLine(null, lineNo, productId, qty, price, amount, BigDecimal.ZERO);
    }

    /** 持久层重建工厂（不重跑业务校验） */
    public static PurchaseOrderLine restore(long id, int lineNo, long productId, BigDecimal quantity,
                                            BigDecimal unitPrice, BigDecimal amount, BigDecimal receivedQty) {
        return new PurchaseOrderLine(id, lineNo, productId, quantity, unitPrice, amount, receivedQty);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("采购订单行 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    /** 本行未到货数量（= 订购量 − 已到货量，永不为负） */
    public BigDecimal outstandingQty() {
        return quantity.subtract(receivedQty);
    }

    /**
     * 累加本次到货数量（采购入库单 M3-T06 过账时由 {@link PurchaseOrder} 编排调用）。
     * 累加后已到货量不得超过订购量（部分收货校验，超量拒绝——宁可拒绝，不可破坏模型）。
     *
     * @param delta 本次到货数量（> 0，基本单位）
     */
    void addReceived(BigDecimal delta) {
        if (delta == null || delta.signum() <= 0) {
            throw new IllegalArgumentException("本次到货数量必须大于 0: "
                    + (delta == null ? "null" : delta.toPlainString()));
        }
        BigDecimal next = receivedQty.add(delta);
        if (next.compareTo(quantity) > 0) {
            throw new IllegalArgumentException("行号 " + lineNo + " 累计到货数量 "
                    + next.toPlainString() + " 超过订购数量 " + quantity.toPlainString()
                    + "（剩余可收 " + outstandingQty().toPlainString() + "）");
        }
        this.receivedQty = next.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING);
    }

    /**
     * 回滚本次到货数量（M4-T07b 采购入库红冲时由 {@link PurchaseOrder} 编排回写）。
     * delta &gt; 0 校验；回滚后已到货量不得 &lt; 0（守门，防回滚多于已到货虚减——保模型不破碎）。
     *
     * @param delta 本次回滚的到货数量（&gt; 0，基本单位）
     */
    void subtractReceived(BigDecimal delta) {
        if (delta == null || delta.signum() <= 0) {
            throw new IllegalArgumentException("本次回滚到货数量必须大于 0: "
                    + (delta == null ? "null" : delta.toPlainString()));
        }
        BigDecimal next = receivedQty.subtract(delta);
        if (next.signum() < 0) {
            throw new IllegalArgumentException("行号 " + lineNo + " 回滚到货数量 "
                    + delta.toPlainString() + " 超过已到货量 " + receivedQty.toPlainString());
        }
        this.receivedQty = next.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING);
    }

    private static BigDecimal normalizedQuantity(BigDecimal quantity) {
        if (quantity == null) {
            throw new IllegalArgumentException("订购数量不能为空");
        }
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("订购数量必须大于 0: " + quantity.toPlainString());
        }
        if (quantity.stripTrailingZeros().scale() > CostingStrategy.UNIT_COST_SCALE) {
            throw new IllegalArgumentException("订购数量最多 " + CostingStrategy.UNIT_COST_SCALE
                    + " 位小数（基本单位记账）: " + quantity.toPlainString());
        }
        return quantity.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING);
    }

    private static BigDecimal normalizedUnitPrice(BigDecimal unitPrice) {
        if (unitPrice == null) {
            throw new IllegalArgumentException("采购单价不能为空");
        }
        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("采购单价不能为负: " + unitPrice.toPlainString());
        }
        if (unitPrice.stripTrailingZeros().scale() > CostingStrategy.UNIT_COST_SCALE) {
            throw new IllegalArgumentException("采购单价最多 " + CostingStrategy.UNIT_COST_SCALE
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

    public BigDecimal getReceivedQty() {
        return receivedQty;
    }
}
