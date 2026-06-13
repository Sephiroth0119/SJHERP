package com.sjherp.domain.sales;

import java.math.BigDecimal;
import java.util.Objects;

import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 销售出库单行项目（M3-T09，路线图 §5 销售线）。
 *
 * <p>一行 = 对应销售订单某行（{@link #soLineNo}）的一次发货：发货数量 + 出库后回填的
 * <b>COGS（销货成本）</b>。
 *
 * <h2>COGS 是什么、记在哪</h2>
 * 出库过账经库存唯一写入口（SALES_OUT），库存服务按<b>移动加权</b>计算出库时点的销货成本
 * （{@code StockMovementResult.totalCost}）。出库服务在过账后把这个成本（取正数口径）
 * 回写到本行 {@link #cogsAmount}——它是该次发货商品的真实成本，供 M4 利润核算（利润 = 售价 − COGS）。
 *
 * <p>建单时 cogsAmount 为 null（尚未过账，成本未知），过账后由
 * {@link #assignCogs(BigDecimal)} 回填。数量/金额一律 {@link BigDecimal}（CLAUDE.md 原则 5）。
 */
public final class SalesDeliveryLine {

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 出库单行号（单据内从 1 起，幂等键 SALES_DELIVERY:SD-xxx:行号 用此） */
    private final int lineNo;

    /** 关联的销售订单行号（本次发货针对订单的哪一行） */
    private final int soLineNo;

    private final long productId;

    /** 发货数量（基本单位，6 位小数，> 0；≤ 订单行剩余可发量，校验在出库服务） */
    private final BigDecimal quantity;

    /** 销货成本 COGS（2 位，正数口径）：建单为 null，过账后回填（移动加权出库成本） */
    private BigDecimal cogsAmount;

    /**
     * 累计已开票数量（基本单位，6 位小数，初始 0；销售发票过账时累加，永不超过 quantity）。
     * 防跨发票超额开票虚增应收（CLAUDE.md 原则 2）：剩余可开票量 = quantity − invoicedQty。
     */
    private BigDecimal invoicedQty;

    private SalesDeliveryLine(Long id, int lineNo, int soLineNo, long productId,
                             BigDecimal quantity, BigDecimal cogsAmount, BigDecimal invoicedQty) {
        this.id = id;
        this.lineNo = lineNo;
        this.soLineNo = soLineNo;
        this.productId = productId;
        this.quantity = Objects.requireNonNull(quantity, "发货数量不能为空");
        this.cogsAmount = cogsAmount;
        this.invoicedQty = Objects.requireNonNull(invoicedQty, "已开票数量不能为空");
    }

    /**
     * 建单工厂：出库行号、关联订单行号、商品、发货数量；COGS 过账后回填。
     *
     * @param lineNo    出库行号（>=1）
     * @param soLineNo  关联销售订单行号（>=1）
     * @param productId 商品 id
     * @param quantity  发货数量（基本单位，> 0，最多 6 位小数）
     */
    public static SalesDeliveryLine create(int lineNo, int soLineNo, long productId, BigDecimal quantity) {
        if (lineNo < 1) {
            throw new IllegalArgumentException("出库单行号必须 >= 1: " + lineNo);
        }
        if (soLineNo < 1) {
            throw new IllegalArgumentException("关联订单行号必须 >= 1: " + soLineNo);
        }
        validateQuantity(quantity);
        return new SalesDeliveryLine(null, lineNo, soLineNo, productId,
                quantity.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING), null,
                BigDecimal.ZERO.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING));
    }

    /** 持久层重建工厂（不重跑业务校验，含已过账回填的 COGS 与累计已开票量） */
    public static SalesDeliveryLine restore(long id, int lineNo, int soLineNo, long productId,
                                            BigDecimal quantity, BigDecimal cogsAmount,
                                            BigDecimal invoicedQty) {
        return new SalesDeliveryLine(id, lineNo, soLineNo, productId, quantity, cogsAmount, invoicedQty);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("出库单行 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    /**
     * 过账后回填 COGS（移动加权出库成本，正数口径，2 位）。只允许在尚未回填时调用一次。
     *
     * @param cogsAmount 销货成本（>=0）
     */
    public void assignCogs(BigDecimal cogsAmount) {
        Objects.requireNonNull(cogsAmount, "COGS 不能为空");
        if (cogsAmount.signum() < 0) {
            throw new IllegalArgumentException("COGS 不能为负: " + cogsAmount.toPlainString());
        }
        this.cogsAmount = cogsAmount.setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }

    /** 本行剩余可开票量（= 发货量 − 已开票量，永不为负） */
    public BigDecimal outstandingInvoiceableQty() {
        return quantity.subtract(invoicedQty);
    }

    /**
     * 累加本次开票数量（销售发票 M3-T10 过账时由 {@link SalesDeliveryService} 回写）。
     * 累加后已开票量不得超过发货量（跨发票累计校验，超量拒绝——宁可拒绝，不可破坏模型，
     * 防跨发票超额开票虚增应收，CLAUDE.md 原则 2）。
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
            throw new IllegalArgumentException("行号 " + lineNo + "（商品 " + productId
                    + "）累计开票数量 " + next.toPlainString() + " 超过发货数量 " + quantity.toPlainString()
                    + "（剩余可开票 " + outstandingInvoiceableQty().toPlainString() + "）");
        }
        this.invoicedQty = next.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING);
    }

    private static void validateQuantity(BigDecimal quantity) {
        if (quantity == null) {
            throw new IllegalArgumentException("发货数量不能为空");
        }
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("发货数量必须大于 0: " + quantity.toPlainString());
        }
        if (quantity.stripTrailingZeros().scale() > CostingStrategy.UNIT_COST_SCALE) {
            throw new IllegalArgumentException("发货数量最多 " + CostingStrategy.UNIT_COST_SCALE
                    + " 位小数（基本单位记账）: " + quantity.toPlainString());
        }
    }

    public Long getId() {
        return id;
    }

    public int getLineNo() {
        return lineNo;
    }

    public int getSoLineNo() {
        return soLineNo;
    }

    public long getProductId() {
        return productId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    /** 销货成本（建单/未过账时为 null） */
    public BigDecimal getCogsAmount() {
        return cogsAmount;
    }

    /** 累计已开票数量（建单为 0，发票过账时回写） */
    public BigDecimal getInvoicedQty() {
        return invoicedQty;
    }
}
