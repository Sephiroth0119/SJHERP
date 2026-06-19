package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.util.Objects;

import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 退料单行（从属 {@link MaterialReturn} 聚合，M5-T04）。
 *
 * <p>一行 = 一个子件的一次退料：退料数量 + 过账后回填的<b>退料成本</b>。
 * 退料按原领料成本入库（InboundCommand 显式 unitCost=issuedCost/quantity，避期间漂移）。
 */
public final class MaterialReturnLine {

    /** 数据库自增主键（持久化后回填） */
    private Long id;

    /** 行号（单据内从 1 起） */
    private final int lineNo;

    /** 子件商品 id */
    private final long productId;

    /** 退料数量（6 位小数，> 0） */
    private final BigDecimal quantity;

    /** 计量单位 id */
    private final long unitId;

    /**
     * 退料成本（正数口径，2 位小数）：建单时为 null，过账后回填
     * （= unitCost × quantity，对应 PRODUCTION_RETURN 入库 totalCost 绝对值）。
     */
    private BigDecimal returnedCost;

    /** 原领料单行号（可选，追溯用） */
    private final Integer srcIssueLineNo;

    private MaterialReturnLine(Long id, int lineNo, long productId, BigDecimal quantity, long unitId,
                                BigDecimal returnedCost, Integer srcIssueLineNo) {
        this.id = id;
        this.lineNo = lineNo;
        this.productId = productId;
        this.quantity = Objects.requireNonNull(quantity, "退料数量不能为空");
        this.unitId = unitId;
        this.returnedCost = returnedCost;
        this.srcIssueLineNo = srcIssueLineNo;
    }

    /**
     * 建单工厂。
     *
     * @param lineNo         行号（≥ 1）
     * @param productId      子件商品 id
     * @param quantity       退料数量（> 0，6 位小数）
     * @param unitId         计量单位 id
     * @param srcIssueLineNo 原领料单行号（可空）
     */
    public static MaterialReturnLine create(int lineNo, long productId, BigDecimal quantity,
                                             long unitId, Integer srcIssueLineNo) {
        if (lineNo < 1) {
            throw new IllegalArgumentException("退料单行号必须 >= 1: " + lineNo);
        }
        Objects.requireNonNull(quantity, "退料数量不能为空");
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("退料数量必须大于 0: " + quantity.toPlainString());
        }
        return new MaterialReturnLine(null, lineNo, productId,
                quantity.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING),
                unitId, null, srcIssueLineNo);
    }

    /** 持久层重建工厂（含已过账回填的 returnedCost） */
    public static MaterialReturnLine restore(long id, int lineNo, long productId, BigDecimal quantity,
                                              long unitId, BigDecimal returnedCost, Integer srcIssueLineNo) {
        return new MaterialReturnLine(id, lineNo, productId, quantity, unitId, returnedCost, srcIssueLineNo);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("退料单行 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    /**
     * 过账后回填退料成本（正数口径，2 位小数）。只允许在尚未回填时调用一次。
     *
     * @param returnedCost 退料成本（≥ 0）
     */
    public void assignReturnedCost(BigDecimal returnedCost) {
        Objects.requireNonNull(returnedCost, "退料成本不能为空");
        if (returnedCost.signum() < 0) {
            throw new IllegalArgumentException("退料成本不能为负: " + returnedCost.toPlainString());
        }
        this.returnedCost = returnedCost.setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }

    public Long getId() { return id; }
    public int getLineNo() { return lineNo; }
    public long getProductId() { return productId; }
    public BigDecimal getQuantity() { return quantity; }
    public long getUnitId() { return unitId; }
    /** 退料成本（建单/未过账时为 null） */
    public BigDecimal getReturnedCost() { return returnedCost; }
    public Integer getSrcIssueLineNo() { return srcIssueLineNo; }
}
