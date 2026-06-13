package com.sjherp.domain.stocktake;

import java.math.BigDecimal;
import java.util.Objects;

import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 盘点单行项目（M3-T03，拆解 docs/M3拆解-库存与成本.md §1.7）。
 *
 * <p>一行 = 单仓内一个商品的盘点结果。建单时由 app 层用 {@code balanceOf} 填入
 * {@link #snapshotQty 账面快照}；盘点员录入 {@link #countedQty 实盘数量} 后，
 * 差异 {@link #diffQty} = 实盘 − 账面 自动派生（正数盘盈 / 负数盘亏 / 0 无差异）。
 *
 * <p>{@link #enteredUnitCost 录入单价}仅用于<b>零库存盘盈</b>：账面数量为 0 时
 * 加权单价无从派生，过账阶段必须用本字段；非零库存盘盈按当前加权单价入库，本字段可空。
 *
 * <p>数量一律基本单位（多单位换算在调用方完成，本模型不换算——与库存服务同口径，防双重换算）。
 */
public final class StockCountLine {

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    /** 行号（单据内从 1 起，幂等键 STOCK_COUNT:docNo:行号 用此） */
    private final int lineNo;

    private final long productId;

    /** 建单时账面快照数量（基本单位，6 位小数）——盘点的对照基准 */
    private final BigDecimal snapshotQty;

    /** 实盘数量（基本单位，6 位小数）；录入前为 null */
    private BigDecimal countedQty;

    /**
     * 零库存盘盈录入单价（≥0，6 位小数）；非零库存盘盈/盘亏/无差异均可空。
     * 账面数量为 0 且盘盈时过账强制要求本字段（拆解 §1.6.1）。
     */
    private final BigDecimal enteredUnitCost;

    private StockCountLine(Long id, int lineNo, long productId, BigDecimal snapshotQty,
                           BigDecimal countedQty, BigDecimal enteredUnitCost) {
        this.id = id;
        this.lineNo = lineNo;
        this.productId = productId;
        this.snapshotQty = Objects.requireNonNull(snapshotQty, "snapshotQty 不能为空");
        this.countedQty = countedQty;
        this.enteredUnitCost = enteredUnitCost;
    }

    /**
     * 建单工厂：账面快照已知、实盘待录入。
     *
     * @param lineNo          行号（>=1）
     * @param productId       商品 id
     * @param snapshotQty     建单时账面快照数量（基本单位）
     * @param enteredUnitCost 零库存盘盈录入单价（可空，过账阶段按需校验）
     */
    public static StockCountLine create(int lineNo, long productId, BigDecimal snapshotQty,
                                        BigDecimal enteredUnitCost) {
        if (lineNo < 1) {
            throw new IllegalArgumentException("盘点单行号必须 >= 1: " + lineNo);
        }
        Objects.requireNonNull(snapshotQty, "账面快照数量不能为空");
        if (enteredUnitCost != null) {
            validateUnitCost(enteredUnitCost);
        }
        return new StockCountLine(null, lineNo, productId,
                snapshotQty.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING),
                null,
                enteredUnitCost == null ? null
                        : enteredUnitCost.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING));
    }

    /** 持久层重建工厂（不重跑业务校验） */
    public static StockCountLine restore(long id, int lineNo, long productId, BigDecimal snapshotQty,
                                         BigDecimal countedQty, BigDecimal enteredUnitCost) {
        return new StockCountLine(id, lineNo, productId, snapshotQty, countedQty, enteredUnitCost);
    }

    /**
     * 录入实盘数量（仅草稿状态可改，由 {@link StockCountDocument} 守门）：
     * 数量 ≥ 0、最多 6 位小数。
     */
    void enterCounted(BigDecimal counted) {
        if (counted == null) {
            throw new IllegalArgumentException("实盘数量不能为空（行号 " + lineNo + "）");
        }
        if (counted.signum() < 0) {
            throw new IllegalArgumentException("实盘数量不能为负（行号 " + lineNo + "）: "
                    + counted.toPlainString());
        }
        if (counted.stripTrailingZeros().scale() > CostingStrategy.UNIT_COST_SCALE) {
            throw new IllegalArgumentException("实盘数量最多 " + CostingStrategy.UNIT_COST_SCALE
                    + " 位小数（行号 " + lineNo + "）: " + counted.toPlainString());
        }
        this.countedQty = counted.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING);
    }

    /** 是否已录入实盘 */
    public boolean isCounted() {
        return countedQty != null;
    }

    /**
     * 差异数量 = 实盘 − 账面（>0 盘盈 / <0 盘亏 / 0 无差异）；未录入实盘时返回 null。
     */
    public BigDecimal diffQty() {
        if (countedQty == null) {
            return null;
        }
        return countedQty.subtract(snapshotQty);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("盘点单行 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    private static void validateUnitCost(BigDecimal unitCost) {
        if (unitCost.signum() < 0) {
            throw new IllegalArgumentException("录入单价不能为负: " + unitCost.toPlainString());
        }
        if (unitCost.stripTrailingZeros().scale() > CostingStrategy.UNIT_COST_SCALE) {
            throw new IllegalArgumentException("录入单价最多 " + CostingStrategy.UNIT_COST_SCALE
                    + " 位小数: " + unitCost.toPlainString());
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

    public BigDecimal getSnapshotQty() {
        return snapshotQty;
    }

    public BigDecimal getCountedQty() {
        return countedQty;
    }

    public BigDecimal getEnteredUnitCost() {
        return enteredUnitCost;
    }
}
