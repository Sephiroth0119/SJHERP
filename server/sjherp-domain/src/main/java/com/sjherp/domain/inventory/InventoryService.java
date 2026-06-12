package com.sjherp.domain.inventory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.inventory.CostingStrategy.OutboundCost;
import com.sjherp.domain.inventory.InventoryTxnType.Direction;

/**
 * 库存领域服务——库存两表的<b>唯一写入口</b>（M3-T01a，CLAUDE.md 原则 1，路线图 §13 铁律）。
 *
 * <p>纯 Java 零依赖：依赖两个仓储端口 + {@link CostingStrategy} + {@link InventoryPolicy}，
 * 由 app 层装配并加方法级事务（@Transactional，全仓第一个跨表外层事务，D-8 已还）。
 *
 * <p>核心约定：
 * <ul>
 *   <li><b>移动加权口径</b>（拆解 §1.6）：单价 6 位 HALF_UP、金额 2 位 HALF_UP、
 *       用已舍入 total 扣减余额、出空清零吸收尾差；</li>
 *   <li><b>幂等</b>（拆解 §1.3）：idempotencyKey 必填，撞键读回原流水比对参数——
 *       一致返回首次结果（支持单据过账重试），不一致抛 {@link IdempotencyConflictException}；</li>
 *   <li><b>锁顺序</b>（拆解 §1.4）：批量/调拨涉及多个余额行时，按
 *       (warehouseId, productId) 升序排序后依次 lockForUpdate，防死锁；</li>
 *   <li><b>负库存</b>（拆解 §1.5）：默认拒绝（{@link InsufficientStockException}）；
 *       开关放行时成本退化为最近一笔带单价流水的单价（估计值，回正不追溯重算）；</li>
 *   <li>仓库/商品存在性与启用校验在入口层（T01c REST/Agent 工具）完成，
 *       本服务按 T01a 依赖清单只持两个库存端口（见拆解 §3 T01a）。</li>
 * </ul>
 */
public class InventoryService {

    /** 幂等键长度上限（与 V10 列宽契约对齐，T01b 建 VARCHAR(200) + 唯一键） */
    static final int IDEMPOTENCY_KEY_MAX_LENGTH = 200;

    private static final BigDecimal ZERO_QUANTITY =
            BigDecimal.ZERO.setScale(CostingStrategy.UNIT_COST_SCALE);

    private final InventoryBalanceRepository balanceRepository;
    private final InventoryTransactionRepository transactionRepository;
    private final CostingStrategy costingStrategy;
    private final InventoryPolicy policy;

    public InventoryService(InventoryBalanceRepository balanceRepository,
                            InventoryTransactionRepository transactionRepository,
                            CostingStrategy costingStrategy, InventoryPolicy policy) {
        this.balanceRepository = Objects.requireNonNull(balanceRepository);
        this.transactionRepository = Objects.requireNonNull(transactionRepository);
        this.costingStrategy = Objects.requireNonNull(costingStrategy);
        this.policy = Objects.requireNonNull(policy);
    }

    // ---------------------------------------------------------------
    // 写入口（@Audited，operator 参数照全仓约定）
    // ---------------------------------------------------------------

    /** 入库：OPENING/PURCHASE_IN/COUNT_GAIN/TRANSFER_IN，unitCost 规则见 {@link InboundCommand} */
    @Audited(action = "inventory.inbound", targetType = "inventory")
    public StockMovementResult inbound(InboundCommand command, String operator) {
        requireOperator(operator);
        validateInbound(command);
        return findReplay(command).orElseGet(() -> postInbound(command,
                lockBalance(command, operator), operator, Map.of()));
    }

    /** 出库：SALES_OUT/COUNT_LOSS/TRANSFER_OUT，成本由服务按移动加权计算并返回（COGS 来源） */
    @Audited(action = "inventory.outbound", targetType = "inventory")
    public StockMovementResult outbound(OutboundCommand command, String operator) {
        requireOperator(operator);
        validateOutbound(command);
        return findReplay(command).orElseGet(() -> postOutbound(command,
                lockBalance(command, operator), operator));
    }

    /** 成本调整：数量不变只调金额（约束见 {@link CostAdjustCommand}） */
    @Audited(action = "inventory.adjust_cost", targetType = "inventory")
    public StockMovementResult adjustCost(CostAdjustCommand command, String operator) {
        requireOperator(operator);
        validateAdjust(command);
        return findReplay(command).orElseGet(() -> postAdjust(command,
                lockBalance(command, operator), operator));
    }

    /**
     * 批量过账（同事务原子，调拨=一出一入）：先对涉及 (warehouseId, productId)
     * 集合去重升序后依次锁定（拆解 §1.4 锁顺序约定），再按指令顺序逐条过账；
     * 任一条失败异常向上抛，由外层事务整体回滚。
     */
    @Audited(action = "inventory.execute", targetType = "inventory")
    public List<StockMovementResult> execute(List<StockMovementCommand> batch, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(batch, "batch 不能为空");
        if (batch.isEmpty()) {
            throw new IllegalArgumentException("批量过账指令不能为空");
        }
        // 先逐条校验（不写库）：尽早失败，避免无谓锁定
        for (StockMovementCommand command : batch) {
            validate(command);
        }
        // 锁顺序约定：去重 → 升序 → 依次 FOR UPDATE（fake 仓储可记录调用顺序断言）
        Map<BalanceKey, InventoryBalance> locked = new HashMap<>();
        batch.stream().map(BalanceKey::of).distinct().sorted().forEach(key ->
                locked.put(key, balanceRepository.lockForUpdate(key.warehouseId(), key.productId(), operator)));

        Map<String, StockMovementResult> resultsByKey = new LinkedHashMap<>();
        List<StockMovementResult> results = new ArrayList<>(batch.size());
        for (StockMovementCommand command : batch) {
            InventoryBalance balance = locked.get(BalanceKey.of(command));
            StockMovementResult result = findReplay(command).orElseGet(() -> switch (command) {
                case InboundCommand in -> postInbound(in, balance, operator, resultsByKey);
                case OutboundCommand out -> postOutbound(out, balance, operator);
                case CostAdjustCommand adjust -> postAdjust(adjust, balance, operator);
            });
            resultsByKey.put(result.idempotencyKey(), result);
            results.add(result);
        }
        return results;
    }

    /** 只读余额查询：无余额行时返回零视图（数量 0 / 金额 0.00） */
    public InventoryBalanceView balanceOf(long warehouseId, long productId) {
        return balanceRepository.find(warehouseId, productId)
                .map(balance -> new InventoryBalanceView(warehouseId, productId,
                        balance.getQuantity(), balance.getCostAmount()))
                .orElseGet(() -> InventoryBalanceView.empty(warehouseId, productId));
    }

    // ---------------------------------------------------------------
    // 过账实现（调用前必须已完成校验并持有余额行锁）
    // ---------------------------------------------------------------

    /** 入库过账（拆解 §1.6.1/§1.6.5）：total = round2(unitCost × qty)，调拨入取调出原值 */
    private StockMovementResult postInbound(InboundCommand command, InventoryBalance balance,
                                            String operator, Map<String, StockMovementResult> batchResults) {
        BigDecimal quantity = scaleQuantity(command.quantity());
        BigDecimal unitCost;
        BigDecimal totalCost;
        if (command.txnType() == InventoryTxnType.TRANSFER_IN) {
            // 调拨入：用调出流水的 total/qty 原值入库（金额守恒，不重新加权舍入）
            TransferSource source = resolveTransferSource(command, batchResults);
            if (source.quantity().compareTo(quantity) != 0) {
                throw new IllegalArgumentException("调拨入数量必须与调出一致: 调出 "
                        + source.quantity().toPlainString() + "，调入 " + quantity.toPlainString());
            }
            unitCost = source.unitCost();
            totalCost = source.totalCost();
        } else if (command.unitCost() == null) {
            // 盘盈默认按当前加权单价入库；零库存盘盈必须指定成本（拆解 §1.6.1）
            if (balance.getQuantity().signum() <= 0) {
                throw new IllegalArgumentException("零库存盘盈必须指定成本: 仓库[" + command.warehouseId()
                        + "] 商品[" + command.productId() + "] 当前结存数量 "
                        + balance.getQuantity().toPlainString());
            }
            unitCost = costingStrategy.weightedUnitCost(balance.getQuantity(), balance.getCostAmount());
            totalCost = costingStrategy.roundedTotal(unitCost, quantity);
        } else {
            unitCost = scaleUnitCost(command.unitCost());
            totalCost = costingStrategy.roundedTotal(unitCost, quantity);
        }
        return post(command, balance, quantity, unitCost, totalCost, operator);
    }

    /** 出库过账（拆解 §1.6.2 + §1.5 负库存口径） */
    private StockMovementResult postOutbound(OutboundCommand command, InventoryBalance balance,
                                             String operator) {
        BigDecimal quantity = scaleQuantity(command.quantity());
        BigDecimal quantityBefore = balance.getQuantity();
        if (quantityBefore.subtract(quantity).signum() < 0 && !policy.allowNegativeStock()) {
            throw new InsufficientStockException(command.warehouseId(), command.productId(),
                    quantityBefore, quantity);
        }
        BigDecimal unitCost;
        BigDecimal totalCost;
        if (quantityBefore.signum() > 0) {
            // 出库前有存量：照常加权（含出空清零兜底）；负库存放行且超量出库时
            // 同样按本口径（出库前单价 × 全量），负库存期间成本为估计值
            OutboundCost cost = costingStrategy.priceOutbound(quantity, quantityBefore,
                    balance.getCostAmount());
            unitCost = cost.unitCost();
            totalCost = cost.totalCost();
        } else {
            // 负库存放行 + 出库前数量 ≤ 0：单价无法加权，退化取最近一笔带单价流水的
            // 单价（估计值，回正后不追溯重算）；连流水都没有则仍拒绝
            unitCost = transactionRepository
                    .findLatestWithUnitCost(command.warehouseId(), command.productId())
                    .map(InventoryTransaction::getUnitCost)
                    .orElseThrow(() -> InsufficientStockException.noCostBasis(command.warehouseId(),
                            command.productId(), quantityBefore, quantity));
            totalCost = costingStrategy.roundedTotal(unitCost, quantity);
        }
        return post(command, balance, quantity.negate(), unitCost, totalCost.negate(), operator);
    }

    /** 成本调整过账（拆解 §1.6.4）：quantity > 0 且调整后金额 ≥ 0，否则拒绝 */
    private StockMovementResult postAdjust(CostAdjustCommand command, InventoryBalance balance,
                                           String operator) {
        BigDecimal adjustAmount = scaleAmount(command.adjustAmount());
        if (balance.getQuantity().signum() <= 0) {
            throw new IllegalArgumentException("成本调整要求当前结存数量大于 0（无数量无成本可调）: 仓库["
                    + command.warehouseId() + "] 商品[" + command.productId() + "] 当前结存数量 "
                    + balance.getQuantity().toPlainString());
        }
        if (balance.getCostAmount().add(adjustAmount).signum() < 0) {
            throw new IllegalArgumentException("成本调整后结存金额不能为负: 当前金额 "
                    + balance.getCostAmount().toPlainString() + "，调整额 "
                    + adjustAmount.toPlainString());
        }
        return post(command, balance, ZERO_QUANTITY, null, adjustAmount, operator);
    }

    /** 统一过账尾步：余额按已舍入增量累加 → 落余额 → 落流水（含过账后余额快照） */
    private StockMovementResult post(StockMovementCommand command, InventoryBalance balance,
                                     BigDecimal signedQuantity, BigDecimal unitCost,
                                     BigDecimal signedTotal, String operator) {
        balance.post(signedQuantity, signedTotal, operator);
        balanceRepository.save(balance);
        InventoryTransaction transaction = new InventoryTransaction(command.warehouseId(),
                command.productId(), command.txnType(), signedQuantity, unitCost, signedTotal,
                balance.getQuantity(), balance.getCostAmount(), command.srcDocType(),
                command.srcDocNo(), command.srcLineNo(), command.idempotencyKey(), operator);
        transactionRepository.save(transaction);
        return toResult(transaction);
    }

    private InventoryBalance lockBalance(StockMovementCommand command, String operator) {
        return balanceRepository.lockForUpdate(command.warehouseId(), command.productId(), operator);
    }

    // ---------------------------------------------------------------
    // 幂等（拆解 §1.3）：同键同参返回首次结果，同键不同参抛异常
    // ---------------------------------------------------------------

    private Optional<StockMovementResult> findReplay(StockMovementCommand command) {
        return transactionRepository.findByIdempotencyKey(command.idempotencyKey())
                .map(existing -> {
                    String mismatch = describeMismatch(existing, command);
                    if (mismatch != null) {
                        throw new IdempotencyConflictException(command.idempotencyKey(), mismatch);
                    }
                    return toResult(existing);
                });
    }

    /** 参数一致性比对：返回 null 表示一致（真幂等），否则返回差异描述 */
    private String describeMismatch(InventoryTransaction existing, StockMovementCommand command) {
        if (existing.getWarehouseId() != command.warehouseId()) {
            return "warehouseId 原 " + existing.getWarehouseId() + " / 新 " + command.warehouseId();
        }
        if (existing.getProductId() != command.productId()) {
            return "productId 原 " + existing.getProductId() + " / 新 " + command.productId();
        }
        if (existing.getTxnType() != command.txnType()) {
            return "txnType 原 " + existing.getTxnType() + " / 新 " + command.txnType();
        }
        BigDecimal expectedQuantity = switch (command) {
            case InboundCommand in -> scaleQuantity(in.quantity());
            case OutboundCommand out -> scaleQuantity(out.quantity()).negate();
            case CostAdjustCommand ignored -> ZERO_QUANTITY;
        };
        if (existing.getQuantity().compareTo(expectedQuantity) != 0) {
            return "quantity 原 " + existing.getQuantity().toPlainString()
                    + " / 新 " + expectedQuantity.toPlainString();
        }
        if (command instanceof InboundCommand in && in.unitCost() != null
                && (existing.getUnitCost() == null
                        || existing.getUnitCost().compareTo(scaleUnitCost(in.unitCost())) != 0)) {
            return "unitCost 原 " + (existing.getUnitCost() == null ? "-"
                    : existing.getUnitCost().toPlainString()) + " / 新 " + in.unitCost().toPlainString();
        }
        if (command instanceof CostAdjustCommand adjust
                && existing.getTotalCost().compareTo(scaleAmount(adjust.adjustAmount())) != 0) {
            return "adjustAmount 原 " + existing.getTotalCost().toPlainString()
                    + " / 新 " + adjust.adjustAmount().toPlainString();
        }
        if (!Objects.equals(existing.getSrcDocType(), command.srcDocType())
                || !Objects.equals(existing.getSrcDocNo(), command.srcDocNo())
                || !Objects.equals(existing.getSrcLineNo(), command.srcLineNo())) {
            return "来源单据 原 " + existing.getSrcDocType() + ":" + existing.getSrcDocNo() + ":"
                    + existing.getSrcLineNo() + " / 新 " + command.srcDocType() + ":"
                    + command.srcDocNo() + ":" + command.srcLineNo();
        }
        return null;
    }

    // ---------------------------------------------------------------
    // 调拨入成本来源解析（拆解 §1.6.5）
    // ---------------------------------------------------------------

    /** 调拨出原值（正数口径）：同批次结果优先（两腿同事务），其次查已落库流水（分次调用重试） */
    private TransferSource resolveTransferSource(InboundCommand command,
                                                 Map<String, StockMovementResult> batchResults) {
        String key = command.transferOutKey();
        StockMovementResult inBatch = batchResults.get(key);
        if (inBatch != null) {
            requireTransferOut(inBatch.txnType(), inBatch.productId(), command, key);
            return new TransferSource(inBatch.unitCost(), inBatch.totalCost().negate(),
                    inBatch.quantity().negate());
        }
        InventoryTransaction persisted = transactionRepository.findByIdempotencyKey(key)
                .orElseThrow(() -> new IllegalArgumentException("调拨入找不到对应的调出流水: " + key));
        requireTransferOut(persisted.getTxnType(), persisted.getProductId(), command, key);
        return new TransferSource(persisted.getUnitCost(), persisted.getTotalCost().negate(),
                persisted.getQuantity().negate());
    }

    private static void requireTransferOut(InventoryTxnType sourceType, long sourceProductId,
                                           InboundCommand command, String key) {
        if (sourceType != InventoryTxnType.TRANSFER_OUT) {
            throw new IllegalArgumentException("调拨入引用的流水不是调拨出: " + key + "（实际 " + sourceType + "）");
        }
        if (sourceProductId != command.productId()) {
            throw new IllegalArgumentException("调拨入商品与调出流水不一致: 调出商品[" + sourceProductId
                    + "]，调入商品[" + command.productId() + "]");
        }
    }

    /** 调拨出腿的成本原值（正数口径） */
    private record TransferSource(BigDecimal unitCost, BigDecimal totalCost, BigDecimal quantity) {
    }

    // ---------------------------------------------------------------
    // 校验（违反抛 IllegalArgumentException 族，拆解 §1.3）
    // ---------------------------------------------------------------

    private void validate(StockMovementCommand command) {
        Objects.requireNonNull(command, "command 不能为空");
        switch (command) {
            case InboundCommand in -> validateInbound(in);
            case OutboundCommand out -> validateOutbound(out);
            case CostAdjustCommand adjust -> validateAdjust(adjust);
        }
    }

    private void validateInbound(InboundCommand command) {
        validateCommon(command, Direction.IN, "入库");
        validateQuantity(command.quantity());
        if (command.txnType() == InventoryTxnType.TRANSFER_IN) {
            if (command.transferOutKey() == null || command.transferOutKey().isBlank()) {
                throw new IllegalArgumentException("调拨入必须提供调出流水幂等键 transferOutKey（成本取调出原值）");
            }
            if (command.unitCost() != null) {
                throw new IllegalArgumentException("调拨入不允许指定单价（成本取调出流水原值，金额守恒）");
            }
            return;
        }
        if (command.transferOutKey() != null) {
            throw new IllegalArgumentException("transferOutKey 仅调拨入（TRANSFER_IN）可用");
        }
        if (command.unitCost() == null) {
            if (command.txnType() != InventoryTxnType.COUNT_GAIN) {
                throw new IllegalArgumentException("入库单价不能为空（仅盘盈可省略，按当前加权单价入库）");
            }
            // COUNT_GAIN 允许为空；零库存时在过账阶段拒绝（需读取余额）
        } else {
            validateUnitCost(command.unitCost());
        }
    }

    private void validateOutbound(OutboundCommand command) {
        validateCommon(command, Direction.OUT, "出库");
        validateQuantity(command.quantity());
    }

    private void validateAdjust(CostAdjustCommand command) {
        validateCommon(command, Direction.NEUTRAL, "成本调整");
        BigDecimal adjustAmount = command.adjustAmount();
        if (adjustAmount == null) {
            throw new IllegalArgumentException("调整额不能为空");
        }
        if (adjustAmount.signum() == 0) {
            throw new IllegalArgumentException("调整额不能为 0");
        }
        if (adjustAmount.stripTrailingZeros().scale() > CostingStrategy.AMOUNT_SCALE) {
            throw new IllegalArgumentException("调整额最多 " + CostingStrategy.AMOUNT_SCALE
                    + " 位小数: " + adjustAmount.toPlainString());
        }
    }

    private void validateCommon(StockMovementCommand command, Direction direction, String actionLabel) {
        Objects.requireNonNull(command, "command 不能为空");
        if (command.txnType() == null || command.txnType().direction() != direction) {
            throw new IllegalArgumentException(actionLabel + "流水类型不合法: " + command.txnType());
        }
        String key = command.idempotencyKey();
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("幂等键 idempotencyKey 必填（约定 docType:docNo:lineNo）");
        }
        if (key.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException("幂等键不能超过 " + IDEMPOTENCY_KEY_MAX_LENGTH + " 个字符");
        }
        if (command.srcDocType() == null || command.srcDocType().isBlank()) {
            throw new IllegalArgumentException("来源单据类型 srcDocType 必填（每笔流水必须可追溯）");
        }
        if (command.srcDocNo() == null || command.srcDocNo().isBlank()) {
            throw new IllegalArgumentException("来源单据号 srcDocNo 必填（每笔流水必须可追溯）");
        }
    }

    private static void validateQuantity(BigDecimal quantity) {
        if (quantity == null) {
            throw new IllegalArgumentException("数量不能为空");
        }
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("数量必须大于 0: " + quantity.toPlainString());
        }
        if (quantity.stripTrailingZeros().scale() > CostingStrategy.UNIT_COST_SCALE) {
            throw new IllegalArgumentException("数量最多 " + CostingStrategy.UNIT_COST_SCALE
                    + " 位小数（基本单位记账）: " + quantity.toPlainString());
        }
    }

    private static void validateUnitCost(BigDecimal unitCost) {
        if (unitCost.signum() < 0) {
            throw new IllegalArgumentException("入库单价不能为负: " + unitCost.toPlainString());
        }
        if (unitCost.stripTrailingZeros().scale() > CostingStrategy.UNIT_COST_SCALE) {
            throw new IllegalArgumentException("入库单价最多 " + CostingStrategy.UNIT_COST_SCALE
                    + " 位小数: " + unitCost.toPlainString());
        }
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    private static StockMovementResult toResult(InventoryTransaction transaction) {
        return new StockMovementResult(transaction.getId(), transaction.getWarehouseId(),
                transaction.getProductId(), transaction.getTxnType(), transaction.getQuantity(),
                transaction.getUnitCost(), transaction.getTotalCost(),
                transaction.getBalanceQuantityAfter(), transaction.getBalanceAmountAfter(),
                transaction.getSrcDocType(), transaction.getSrcDocNo(), transaction.getSrcLineNo(),
                transaction.getIdempotencyKey());
    }

    private static BigDecimal scaleQuantity(BigDecimal quantity) {
        return quantity.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING);
    }

    private static BigDecimal scaleUnitCost(BigDecimal unitCost) {
        return unitCost.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING);
    }

    private static BigDecimal scaleAmount(BigDecimal amount) {
        return amount.setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }

    /** 余额行锁键：按 (warehouseId, productId) 升序（拆解 §1.4 锁顺序约定） */
    private record BalanceKey(long warehouseId, long productId) implements Comparable<BalanceKey> {

        static BalanceKey of(StockMovementCommand command) {
            return new BalanceKey(command.warehouseId(), command.productId());
        }

        @Override
        public int compareTo(BalanceKey other) {
            int byWarehouse = Long.compare(this.warehouseId, other.warehouseId);
            return byWarehouse != 0 ? byWarehouse : Long.compare(this.productId, other.productId);
        }
    }
}
