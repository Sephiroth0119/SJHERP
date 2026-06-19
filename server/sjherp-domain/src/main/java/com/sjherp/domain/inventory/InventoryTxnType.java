package com.sjherp.domain.inventory;

/**
 * 库存流水类型（M3-T01a，拆解 §1.2）。
 *
 * <p>每个类型固定方向（IN/OUT/NEUTRAL），与流水 quantity 符号由
 * {@link InventoryService} 强制一致：入库为正、出库为负、成本调整为 0。
 * 带符号设计使对账 SQL 一行写完：Σquantity = 余额数量、Σtotal_cost = 余额金额。
 */
public enum InventoryTxnType {

    /** 期初建账（M2-T09 期初导入，doc_type=OPENING） */
    OPENING(Direction.IN, "期初"),

    /** 采购入库 */
    PURCHASE_IN(Direction.IN, "采购入库"),

    /** 销售出库（出库成本即 COGS，由服务按移动加权计算） */
    SALES_OUT(Direction.OUT, "销售出库"),

    /** 盘盈（默认按当前加权单价入库；零库存盘盈必须指定成本） */
    COUNT_GAIN(Direction.IN, "盘盈"),

    /** 盘亏（按出库口径计成本） */
    COUNT_LOSS(Direction.OUT, "盘亏"),

    /** 调拨入（成本取调出流水的 total/qty 原值，金额守恒，两腿同事务） */
    TRANSFER_IN(Direction.IN, "调拨入"),

    /** 调拨出（按出库口径计成本） */
    TRANSFER_OUT(Direction.OUT, "调拨出"),

    /** 生产领料（出库消耗，成本按移动加权计算） */
    PRODUCTION_ISSUE(Direction.OUT, "生产领料"),

    /** 生产退料（入库还原，按原领料成本入回） */
    PRODUCTION_RETURN(Direction.IN, "生产退料"),

    /** 成本调整（数量不变只调金额，典型场景：到票价差、运费入成本） */
    COST_ADJUST(Direction.NEUTRAL, "成本调整");

    /** 库存方向：入库（数量为正）/ 出库（数量为负）/ 中性（数量为 0，只动金额） */
    public enum Direction { IN, OUT, NEUTRAL }

    private final Direction direction;
    private final String label;

    InventoryTxnType(Direction direction, String label) {
        this.direction = direction;
        this.label = label;
    }

    public Direction direction() {
        return direction;
    }

    /** 中文展示名（审计摘要、Agent 文案用） */
    public String label() {
        return label;
    }
}
