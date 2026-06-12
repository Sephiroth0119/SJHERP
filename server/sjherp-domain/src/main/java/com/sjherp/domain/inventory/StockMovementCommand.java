package com.sjherp.domain.inventory;

/**
 * 库存移动指令公共契约（M3-T01a，拆解 §1.3）。
 *
 * <p>公共字段约定：
 * <ul>
 *   <li>quantity 一律<b>正数、基本单位</b>（多单位换算在单据行层完成，本服务不做换算——防双重换算）；
 *       符号由服务按 {@link InventoryTxnType.Direction} 统一落到流水；</li>
 *   <li>idempotencyKey <b>必填</b>，约定格式 {@code docType:docNo:lineNo}
 *       （如 {@code SALES_DELIVERY:SD-202606-0001:1}），同键同参返回首次结果，
 *       同键不同参抛 {@link IdempotencyConflictException}；</li>
 *   <li>srcDocType/srcDocNo 必填、srcLineNo 可空：每笔流水必须可追溯到来源单据。</li>
 * </ul>
 */
public sealed interface StockMovementCommand permits InboundCommand, OutboundCommand, CostAdjustCommand {

    long warehouseId();

    long productId();

    InventoryTxnType txnType();

    /** 来源单据类型（如 OPENING / PURCHASE_RECEIPT / SALES_DELIVERY / STOCK_COUNT / TRANSFER / COST_ADJUST） */
    String srcDocType();

    /** 来源单据号 */
    String srcDocNo();

    /** 来源单据行号，可空（整单一行时可不填） */
    Integer srcLineNo();

    /** 幂等键（必填，租户内唯一，数据库唯一键兜底） */
    String idempotencyKey();
}
