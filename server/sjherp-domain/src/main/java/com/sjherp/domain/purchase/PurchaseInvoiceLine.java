package com.sjherp.domain.purchase;

import java.math.BigDecimal;
import java.util.Objects;

import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 采购发票行项目（M3-T07）。
 *
 * <p>一行 = 引用某采购入库单行（{@link #receiptLineNo}）的开票：商品、开票数量、开票金额。
 * 三单匹配从简（订单-入库-发票）：发票行开票数量 ≤ 已收数量（即收货单对应行的数量），
 * 该校验在 {@link PurchaseInvoiceService} 引用收货单时执行；行本身只守门数量 > 0、金额 ≥ 0。
 *
 * <p>发票金额可与「数量 × 收货单价」有合理差异（运费、折扣、税差），故金额由开票方直接给出，
 * 不强制等于数量乘单价——但数量不得超过已收（防超额开票）。
 */
public final class PurchaseInvoiceLine {

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 行号（单据内从 1 起） */
    private final int lineNo;

    /** 引用的采购入库单行号（三单匹配按此行已收数量校验） */
    private final int receiptLineNo;

    private final long productId;

    /** 开票数量（基本单位，6 位小数，> 0，≤ 收货行已收数量） */
    private final BigDecimal quantity;

    /** 开票金额（2 位小数，≥0；本行计入应付的金额） */
    private final BigDecimal amount;

    private PurchaseInvoiceLine(Long id, int lineNo, int receiptLineNo, long productId,
                               BigDecimal quantity, BigDecimal amount) {
        this.id = id;
        this.lineNo = lineNo;
        this.receiptLineNo = receiptLineNo;
        this.productId = productId;
        this.quantity = Objects.requireNonNull(quantity, "开票数量不能为空");
        this.amount = Objects.requireNonNull(amount, "开票金额不能为空");
    }

    /**
     * 建单工厂：行号、引用采购入库单行号、商品、开票数量、开票金额。
     *
     * @param lineNo        行号（>=1）
     * @param receiptLineNo 引用的采购入库单行号（>=1）
     * @param productId     商品 id（须与收货行商品一致，由服务校验）
     * @param quantity      开票数量（基本单位，> 0，最多 6 位小数）
     * @param amount        开票金额（≥0，最多 2 位小数）
     */
    public static PurchaseInvoiceLine create(int lineNo, int receiptLineNo, long productId,
                                             BigDecimal quantity, BigDecimal amount) {
        if (lineNo < 1) {
            throw new IllegalArgumentException("采购发票行号必须 >= 1: " + lineNo);
        }
        if (receiptLineNo < 1) {
            throw new IllegalArgumentException("引用的采购入库单行号必须 >= 1: " + receiptLineNo);
        }
        BigDecimal qty = normalizedQuantity(quantity);
        BigDecimal amt = normalizedAmount(amount);
        return new PurchaseInvoiceLine(null, lineNo, receiptLineNo, productId, qty, amt);
    }

    /** 持久层重建工厂（不重跑业务校验） */
    public static PurchaseInvoiceLine restore(long id, int lineNo, int receiptLineNo, long productId,
                                              BigDecimal quantity, BigDecimal amount) {
        return new PurchaseInvoiceLine(id, lineNo, receiptLineNo, productId, quantity, amount);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("采购发票行 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    private static BigDecimal normalizedQuantity(BigDecimal quantity) {
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

    private static BigDecimal normalizedAmount(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("开票金额不能为空");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("开票金额不能为负: " + amount.toPlainString());
        }
        if (amount.stripTrailingZeros().scale() > CostingStrategy.AMOUNT_SCALE) {
            throw new IllegalArgumentException("开票金额最多 " + CostingStrategy.AMOUNT_SCALE
                    + " 位小数: " + amount.toPlainString());
        }
        return amount.setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }

    public Long getId() {
        return id;
    }

    public int getLineNo() {
        return lineNo;
    }

    public int getReceiptLineNo() {
        return receiptLineNo;
    }

    public long getProductId() {
        return productId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
