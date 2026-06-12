package com.sjherp.domain.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 库存余额行（M3-T01a，拆解 §1.1）：仓库 × 商品一行（batch_id v1.0 恒 0，由 infra 落列）。
 *
 * <p>余额真源是 quantity（DECIMAL(18,6)，基本单位）与 costAmount（DECIMAL(18,2)）两列，
 * 对账只认这两列；加权单价是派生值不存储。余额不是单据，不走 BusinessDocument，
 * 写入只能经 {@link InventoryService}（唯一写入口铁律）。
 */
public final class InventoryBalance {

    /** 数据库自增主键，落库后由仓储回填；null 表示尚未持久化 */
    private Long id;

    private final long warehouseId;
    private final long productId;

    /** 结存数量（基本单位，6 位小数） */
    private BigDecimal quantity;

    /** 结存金额（2 位小数）。负库存关闭（默认）时由出空清零规则保证 ≥ 0 */
    private BigDecimal costAmount;

    private String updatedBy;
    private Instant updatedAt;

    private InventoryBalance(Long id, long warehouseId, long productId,
                             BigDecimal quantity, BigDecimal costAmount,
                             String updatedBy, Instant updatedAt) {
        this.id = id;
        this.warehouseId = warehouseId;
        this.productId = productId;
        this.quantity = quantity;
        this.costAmount = costAmount;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    /** 初始零行（首次过账前由仓储 lockForUpdate 创建，拆解 §1.4） */
    public static InventoryBalance openZero(long warehouseId, long productId, String operator) {
        Objects.requireNonNull(operator, "operator 不能为空");
        return new InventoryBalance(null, warehouseId, productId,
                BigDecimal.ZERO.setScale(CostingStrategy.UNIT_COST_SCALE),
                BigDecimal.ZERO.setScale(CostingStrategy.AMOUNT_SCALE),
                operator, Instant.now());
    }

    /** 持久层重建工厂（不重跑业务校验） */
    public static InventoryBalance restore(long id, long warehouseId, long productId,
                                           BigDecimal quantity, BigDecimal costAmount,
                                           String updatedBy, Instant updatedAt) {
        return new InventoryBalance(id, warehouseId, productId, quantity, costAmount,
                updatedBy, updatedAt);
    }

    /**
     * 过账（仅 {@link InventoryService} 调用）：数量与金额按已舍入的增量累加——
     * 金额增量必须是已 2 位舍入的流水 total_cost（对账恒等式的前提，拆解 §1.6.2d）。
     */
    void post(BigDecimal quantityDelta, BigDecimal amountDelta, String operator) {
        this.quantity = this.quantity.add(quantityDelta)
                .setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING);
        this.costAmount = this.costAmount.add(amountDelta)
                .setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
        this.updatedBy = Objects.requireNonNull(operator, "operator 不能为空");
        this.updatedAt = Instant.now();
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("余额行 id 已分配，不可重复分配: " + this.id);
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

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getCostAmount() {
        return costAmount;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
