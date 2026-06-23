package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.util.Objects;

import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 领料单行（从属 {@link MaterialIssue} 聚合，M5-T04）。
 *
 * <p>一行 = 一个子件的一次领料：应领量（含损耗）+ 实领量 + 过账后回填的<b>领料成本</b>。
 * 照 {@link com.sjherp.domain.sales.SalesDeliveryLine#assignCogs} 范式：issuedCost 建单为 null，
 * 过账后由 {@link #assignIssuedCost(BigDecimal)} 回填一次（正数口径，2 位小数）。
 */
public final class MaterialIssueLine {

    /** 数据库自增主键（持久化后回填） */
    private Long id;

    /** 行号（单据内从 1 起） */
    private final int lineNo;

    /** 子件商品 id */
    private final long productId;

    /** 应领数量（含损耗的计划量，6 位小数，≥ 0） */
    private final BigDecimal requiredQty;

    /** 实领数量（实际领取量，6 位小数，> 0） */
    private final BigDecimal quantity;

    /** 计量单位 id */
    private final long unitId;

    /**
     * 领料成本（正数口径，2 位小数）：建单时为 null，过账后由
     * {@link #assignIssuedCost(BigDecimal)} 回填（对应出库 totalCost 的绝对值）。
     */
    private BigDecimal issuedCost;

    private MaterialIssueLine(Long id, int lineNo, long productId,
                               BigDecimal requiredQty, BigDecimal quantity, long unitId,
                               BigDecimal issuedCost) {
        this.id = id;
        this.lineNo = lineNo;
        this.productId = productId;
        this.requiredQty = Objects.requireNonNull(requiredQty, "应领数量不能为空");
        this.quantity = Objects.requireNonNull(quantity, "实领数量不能为空");
        this.unitId = unitId;
        this.issuedCost = issuedCost;
    }

    /**
     * 建单工厂：行号、子件商品、应领量、实领量、单位；issuedCost 过账后回填。
     *
     * @param lineNo      行号（≥ 1）
     * @param productId   子件商品 id
     * @param requiredQty 应领数量（≥ 0，6 位小数）
     * @param quantity    实领数量（> 0，6 位小数）
     * @param unitId      计量单位 id
     */
    public static MaterialIssueLine create(int lineNo, long productId,
                                           BigDecimal requiredQty, BigDecimal quantity, long unitId) {
        if (lineNo < 1) {
            throw new IllegalArgumentException("领料单行号必须 >= 1: " + lineNo);
        }
        Objects.requireNonNull(requiredQty, "应领数量不能为空");
        if (requiredQty.signum() < 0) {
            throw new IllegalArgumentException("应领数量不能为负: " + requiredQty.toPlainString());
        }
        Objects.requireNonNull(quantity, "实领数量不能为空");
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("实领数量必须大于 0: " + quantity.toPlainString());
        }
        return new MaterialIssueLine(null, lineNo, productId,
                requiredQty.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING),
                quantity.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING),
                unitId, null);
    }

    /** 持久层重建工厂（不重跑业务校验，含已过账回填的 issuedCost） */
    public static MaterialIssueLine restore(long id, int lineNo, long productId,
                                            BigDecimal requiredQty, BigDecimal quantity, long unitId,
                                            BigDecimal issuedCost) {
        return new MaterialIssueLine(id, lineNo, productId, requiredQty, quantity, unitId, issuedCost);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("领料单行 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    /**
     * 过账后回填领料成本（移动加权出库成本的绝对值，正数口径，2 位小数）。
     * 照 SalesDeliveryLine.assignCogs 范式：只允许在尚未回填时调用一次。
     *
     * @param issuedCost 领料成本（≥ 0）
     */
    public void assignIssuedCost(BigDecimal issuedCost) {
        Objects.requireNonNull(issuedCost, "领料成本不能为空");
        if (issuedCost.signum() < 0) {
            throw new IllegalArgumentException("领料成本不能为负: " + issuedCost.toPlainString());
        }
        this.issuedCost = issuedCost.setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }

    public Long getId() { return id; }
    public int getLineNo() { return lineNo; }
    public long getProductId() { return productId; }
    public BigDecimal getRequiredQty() { return requiredQty; }
    public BigDecimal getQuantity() { return quantity; }
    public long getUnitId() { return unitId; }
    /** 领料成本（建单/未过账时为 null） */
    public BigDecimal getIssuedCost() { return issuedCost; }
}
