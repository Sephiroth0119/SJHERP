package com.sjherp.domain.purchase;

import java.math.BigDecimal;
import java.util.Objects;

import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 采购入库单行项目（M3-T06）。
 *
 * <p>一行 = 引用某采购订单行（{@link #poLineNo}）的本次收货：商品、收货数量、收货单价
 * （默认取采购订单行单价，可改）与派生入库金额（= 数量 × 单价）。过账时每行组一笔
 * {@code PURCHASE_IN} 入库流水（unitCost = 收货单价），并把收货量回写到对应采购订单行。
 *
 * <p>部分收货：单行收货数量 ≤ 采购订单行未收量——该校验在 {@link PurchaseReceiptService}
 * 引用采购订单时执行（行本身只守门数量 > 0、单价 ≥ 0）。
 */
public final class PurchaseReceiptLine {

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 行号（单据内从 1 起，幂等键 PURCHASE_RECEIPT:docNo:行号 用此） */
    private final int lineNo;

    /** 引用的采购订单行号（收货量回写到该行的 receivedQty） */
    private final int poLineNo;

    private final long productId;

    /** 收货数量（基本单位，6 位小数，> 0） */
    private final BigDecimal quantity;

    /** 收货单价（≥0，6 位小数，默认取采购订单行单价，可改；即 PURCHASE_IN 的入库单价） */
    private final BigDecimal unitCost;

    /** 入库金额（= 数量 × 单价，2 位小数，派生但落库便于汇总与审计） */
    private final BigDecimal amount;

    /**
     * 累计已开票数量（基本单位，6 位小数，初始 0；采购发票过账时累加，永不超过 quantity）。
     * 防跨发票超额开票虚增应付（CLAUDE.md 原则 2）：剩余可开票量 = quantity − invoicedQty。
     */
    private BigDecimal invoicedQty;

    private PurchaseReceiptLine(Long id, int lineNo, int poLineNo, long productId,
                               BigDecimal quantity, BigDecimal unitCost, BigDecimal amount,
                               BigDecimal invoicedQty) {
        this.id = id;
        this.lineNo = lineNo;
        this.poLineNo = poLineNo;
        this.productId = productId;
        this.quantity = Objects.requireNonNull(quantity, "收货数量不能为空");
        this.unitCost = Objects.requireNonNull(unitCost, "收货单价不能为空");
        this.amount = Objects.requireNonNull(amount, "入库金额不能为空");
        this.invoicedQty = Objects.requireNonNull(invoicedQty, "已开票数量不能为空");
    }

    /**
     * 建单工厂：行号、引用采购订单行号、商品、收货数量、收货单价（金额自动计算）。
     *
     * @param lineNo    行号（>=1）
     * @param poLineNo  引用的采购订单行号（>=1）
     * @param productId 商品 id（须与采购订单行商品一致，由服务校验）
     * @param quantity  收货数量（基本单位，> 0，最多 6 位小数）
     * @param unitCost  收货单价（≥0，最多 6 位小数）
     */
    public static PurchaseReceiptLine create(int lineNo, int poLineNo, long productId,
                                             BigDecimal quantity, BigDecimal unitCost) {
        if (lineNo < 1) {
            throw new IllegalArgumentException("采购入库单行号必须 >= 1: " + lineNo);
        }
        if (poLineNo < 1) {
            throw new IllegalArgumentException("引用的采购订单行号必须 >= 1: " + poLineNo);
        }
        BigDecimal qty = normalizedQuantity(quantity);
        BigDecimal cost = normalizedUnitCost(unitCost);
        BigDecimal amount = cost.multiply(qty).setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
        return new PurchaseReceiptLine(null, lineNo, poLineNo, productId, qty, cost, amount,
                BigDecimal.ZERO.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING));
    }

    /** 持久层重建工厂（不重跑业务校验，含已开票累计量） */
    public static PurchaseReceiptLine restore(long id, int lineNo, int poLineNo, long productId,
                                              BigDecimal quantity, BigDecimal unitCost, BigDecimal amount,
                                              BigDecimal invoicedQty) {
        return new PurchaseReceiptLine(id, lineNo, poLineNo, productId, quantity, unitCost, amount, invoicedQty);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("采购入库单行 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    /** 本行剩余可开票量（= 收货量 − 已开票量，永不为负） */
    public BigDecimal outstandingInvoiceableQty() {
        return quantity.subtract(invoicedQty);
    }

    /**
     * 累加本次开票数量（采购发票 M3-T07 过账时由 {@link PurchaseReceiptService} 回写）。
     * 累加后已开票量不得超过收货量（跨发票累计校验，超量拒绝——宁可拒绝，不可破坏模型，
     * 防跨发票超额开票虚增应付，CLAUDE.md 原则 2）。
     *
     * @param delta 本次开票数量（> 0，基本单位）
     */
    void addInvoiced(BigDecimal delta) {
        if (delta == null || delta.signum() <= 0) {
            throw new IllegalArgumentException("本次开票数量必须大于 0: "
                    + (delta == null ? "null" : delta.toPlainString()));
        }
        BigDecimal next = invoicedQty.add(delta);
        if (next.compareTo(quantity) > 0) {
            throw new IllegalArgumentException("行号 " + lineNo + " 累计开票数量 "
                    + next.toPlainString() + " 超过收货数量 " + quantity.toPlainString()
                    + "（剩余可开票 " + outstandingInvoiceableQty().toPlainString() + "）");
        }
        this.invoicedQty = next.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING);
    }

    private static BigDecimal normalizedQuantity(BigDecimal quantity) {
        if (quantity == null) {
            throw new IllegalArgumentException("收货数量不能为空");
        }
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("收货数量必须大于 0: " + quantity.toPlainString());
        }
        if (quantity.stripTrailingZeros().scale() > CostingStrategy.UNIT_COST_SCALE) {
            throw new IllegalArgumentException("收货数量最多 " + CostingStrategy.UNIT_COST_SCALE
                    + " 位小数（基本单位记账）: " + quantity.toPlainString());
        }
        return quantity.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING);
    }

    private static BigDecimal normalizedUnitCost(BigDecimal unitCost) {
        if (unitCost == null) {
            throw new IllegalArgumentException("收货单价不能为空");
        }
        if (unitCost.signum() < 0) {
            throw new IllegalArgumentException("收货单价不能为负: " + unitCost.toPlainString());
        }
        if (unitCost.stripTrailingZeros().scale() > CostingStrategy.UNIT_COST_SCALE) {
            throw new IllegalArgumentException("收货单价最多 " + CostingStrategy.UNIT_COST_SCALE
                    + " 位小数: " + unitCost.toPlainString());
        }
        return unitCost.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING);
    }

    public Long getId() {
        return id;
    }

    public int getLineNo() {
        return lineNo;
    }

    public int getPoLineNo() {
        return poLineNo;
    }

    public long getProductId() {
        return productId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    /** 累计已开票数量（建单为 0，发票过账时回写） */
    public BigDecimal getInvoicedQty() {
        return invoicedQty;
    }
}
