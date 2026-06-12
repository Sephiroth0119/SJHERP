package com.sjherp.domain.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 库存流水（M3-T01a，拆解 §1.2）：<b>只插入、不更新不删除</b>，纠错走反向流水
 * （红字单驱动）。流水是过账结果不是单据，无生命周期，不走 BusinessDocument。
 *
 * <p>列口径：quantity 带符号（入正/出负/成本调整 0）；unitCost 入库=入库单价、
 * 出库=出库时点加权单价快照（仅时点快照不参与后续计算）、成本调整=null；
 * totalCost 与 quantity 同号（成本调整=调整额可正可负）；balanceQuantityAfter /
 * balanceAmountAfter 为过账后余额快照（对账与排错利器）。
 * tenant_id 与 batch_id（v1.0 恒 0）由 infra 落列，领域层不出现（ADR-002 / Q-2）。
 */
public final class InventoryTransaction {

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    private final long warehouseId;
    private final long productId;
    private final InventoryTxnType txnType;

    /** 带符号数量（基本单位，6 位小数） */
    private final BigDecimal quantity;

    /** 单价快照（6 位小数），成本调整为 null */
    private final BigDecimal unitCost;

    /** 带符号金额（2 位小数），与 quantity 同号 */
    private final BigDecimal totalCost;

    private final BigDecimal balanceQuantityAfter;
    private final BigDecimal balanceAmountAfter;

    private final String srcDocType;
    private final String srcDocNo;
    private final Integer srcLineNo;

    /** 幂等键（租户内唯一，数据库唯一键兜底） */
    private final String idempotencyKey;

    /** 操作人（人工=登录名 / Agent=agent:&lt;userId&gt;） */
    private final String operator;

    private final Instant createdAt;

    /** 新建流水（过账时由 {@link InventoryService} 构造） */
    public InventoryTransaction(long warehouseId, long productId, InventoryTxnType txnType,
                                BigDecimal quantity, BigDecimal unitCost, BigDecimal totalCost,
                                BigDecimal balanceQuantityAfter, BigDecimal balanceAmountAfter,
                                String srcDocType, String srcDocNo, Integer srcLineNo,
                                String idempotencyKey, String operator) {
        this(null, warehouseId, productId, txnType, quantity, unitCost, totalCost,
                balanceQuantityAfter, balanceAmountAfter, srcDocType, srcDocNo, srcLineNo,
                idempotencyKey, operator, Instant.now());
    }

    private InventoryTransaction(Long id, long warehouseId, long productId, InventoryTxnType txnType,
                                 BigDecimal quantity, BigDecimal unitCost, BigDecimal totalCost,
                                 BigDecimal balanceQuantityAfter, BigDecimal balanceAmountAfter,
                                 String srcDocType, String srcDocNo, Integer srcLineNo,
                                 String idempotencyKey, String operator, Instant createdAt) {
        this.id = id;
        this.warehouseId = warehouseId;
        this.productId = productId;
        this.txnType = Objects.requireNonNull(txnType, "txnType 不能为空");
        this.quantity = Objects.requireNonNull(quantity, "quantity 不能为空");
        this.unitCost = unitCost;
        this.totalCost = Objects.requireNonNull(totalCost, "totalCost 不能为空");
        this.balanceQuantityAfter = Objects.requireNonNull(balanceQuantityAfter, "balanceQuantityAfter 不能为空");
        this.balanceAmountAfter = Objects.requireNonNull(balanceAmountAfter, "balanceAmountAfter 不能为空");
        this.srcDocType = Objects.requireNonNull(srcDocType, "srcDocType 不能为空");
        this.srcDocNo = Objects.requireNonNull(srcDocNo, "srcDocNo 不能为空");
        this.srcLineNo = srcLineNo;
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey 不能为空");
        this.operator = Objects.requireNonNull(operator, "operator 不能为空");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不能为空");
    }

    /** 持久层重建工厂（不重跑业务校验） */
    public static InventoryTransaction restore(long id, long warehouseId, long productId,
                                               InventoryTxnType txnType, BigDecimal quantity,
                                               BigDecimal unitCost, BigDecimal totalCost,
                                               BigDecimal balanceQuantityAfter, BigDecimal balanceAmountAfter,
                                               String srcDocType, String srcDocNo, Integer srcLineNo,
                                               String idempotencyKey, String operator, Instant createdAt) {
        return new InventoryTransaction(id, warehouseId, productId, txnType, quantity, unitCost,
                totalCost, balanceQuantityAfter, balanceAmountAfter, srcDocType, srcDocNo, srcLineNo,
                idempotencyKey, operator, createdAt);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("流水 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public long getWarehouseId() {
        return warehouseId;
    }

    public long getProductId() {
        return productId;
    }

    public InventoryTxnType getTxnType() {
        return txnType;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public BigDecimal getTotalCost() {
        return totalCost;
    }

    public BigDecimal getBalanceQuantityAfter() {
        return balanceQuantityAfter;
    }

    public BigDecimal getBalanceAmountAfter() {
        return balanceAmountAfter;
    }

    public String getSrcDocType() {
        return srcDocType;
    }

    public String getSrcDocNo() {
        return srcDocNo;
    }

    public Integer getSrcLineNo() {
        return srcLineNo;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getOperator() {
        return operator;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
