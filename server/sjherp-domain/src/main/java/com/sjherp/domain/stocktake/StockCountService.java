package com.sjherp.domain.stocktake;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InventoryBalanceView;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.OutboundCommand;
import com.sjherp.domain.inventory.StockMovementCommand;

/**
 * 库存盘点单领域服务（M3-T03，拆解 docs/M3拆解-库存与成本.md §1.7 / §1.6.1）。
 *
 * <p>所有盘点写操作的唯一入口（CLAUDE.md 原则 1）。纯 Java 零依赖：依赖盘点仓储端口
 * {@link StockCountRepository} 与库存能力端口 {@link InventoryPostingPort}，
 * 由 app 层装配并把单据状态变更 + 库存过账包进同一外层事务（@Transactional，拆解 §1.4）。
 *
 * <h2>过账口径（财务正确性核心，拆解 §1.6.1）</h2>
 * 审核（APPROVED）后过账：单据 EXECUTING → COMPLETED，对每行按差异 = 实盘 − 账面：
 * <ul>
 *   <li><b>差异 &gt; 0（盘盈）</b>：COUNT_GAIN 入库。入库单价取「当前余额派生加权单价」；
 *       <b>当前数量为 0 时必须用行的录入单价 enteredUnitCost，缺失则拒绝</b>；</li>
 *   <li><b>差异 &lt; 0（盘亏）</b>：COUNT_LOSS 出库，成本由库存服务按移动加权自动算；</li>
 *   <li><b>差异 == 0</b>：跳过（无流水）。</li>
 * </ul>
 * 所有非零行组成一个 {@link StockMovementCommand} 列表，一次 {@link InventoryPostingPort#execute}
 * 同事务原子过账。幂等键 {@code STOCK_COUNT:SC-xxx:行号}。
 *
 * <p>已 COMPLETED 单据的冲销（红字盘点单）见 {@link #reverse} TODO（M4-T07 统一做）。
 */
public class StockCountService {

    /** 库存流水来源单据类型（与拆解 §1.2 src_doc_type 约定一致） */
    static final String SRC_DOC_TYPE = "STOCK_COUNT";

    private final StockCountRepository repository;
    private final InventoryPostingPort inventory;

    /** 领域事件发布器：状态流转经它自动落 document.status_changed 审计（app 装配 SyncDomainEventPublisher） */
    private final DomainEventPublisher eventPublisher;

    public StockCountService(StockCountRepository repository, InventoryPostingPort inventory,
                             DomainEventPublisher eventPublisher) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.inventory = Objects.requireNonNull(inventory, "inventory 不能为空");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher 不能为空");
    }

    /**
     * 创建盘点单（草稿）：行集合（含建单账面快照）由 app 入口层组装后传入。
     *
     * @param docNo       单据号（SC-年月-序号，app 层用 DocumentNumberGenerator 生成）
     * @param warehouseId 盘点仓库 id（存在性/启用校验在 app 入口层）
     * @param remark      盘点说明（可空）
     * @param lines       行输入（商品 + 建单账面快照 + 可选录入单价）
     * @param operator    操作人
     */
    @Audited(action = "stock_count.create", targetType = "stock_count")
    public StockCountDocument create(String docNo, long warehouseId, String remark,
                                     List<StockCountLineInput> lines, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(docNo, "单据号不能为空");
        Objects.requireNonNull(lines, "盘点行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("盘点单至少要有一行");
        }
        List<StockCountLine> domainLines = new ArrayList<>(lines.size());
        int lineNo = 1;
        for (StockCountLineInput input : lines) {
            domainLines.add(StockCountLine.create(lineNo++, input.productId(),
                    input.snapshotQty(), input.enteredUnitCost()));
        }
        StockCountDocument document = StockCountDocument.create(docNo, warehouseId, remark,
                domainLines, operator);
        document.registerEventPublisher(eventPublisher);
        repository.save(document);
        return document;
    }

    /**
     * 录入某行实盘数量（仅草稿可改）。
     *
     * @param docNo   盘点单号
     * @param lineNo  行号
     * @param counted 实盘数量（≥0，基本单位）
     */
    @Audited(action = "stock_count.enter_count", targetType = "stock_count")
    public StockCountDocument enterCount(String docNo, int lineNo, BigDecimal counted, String operator) {
        requireOperator(operator);
        StockCountDocument document = get(docNo);
        document.enterCounted(lineNo, counted);
        repository.save(document);
        return document;
    }

    /**
     * 审核盘点单：DRAFT → APPROVED（实盘数据自此锁定；审核前必须每行都已录入实盘，
     * 由单据 {@code beforeTransition} 守门）。
     */
    @Audited(action = "stock_count.approve", targetType = "stock_count")
    public StockCountDocument approve(String docNo, String operator) {
        requireOperator(operator);
        StockCountDocument document = get(docNo);
        document.registerEventPublisher(eventPublisher);
        document.approve(operator);
        repository.save(document);
        return document;
    }

    /**
     * 过账盘点单：APPROVED → EXECUTING → COMPLETED，对各非零差异行按 §1.6.1 口径生成
     * 盘盈/盘亏流水，组成一批同事务原子过账（{@link InventoryPostingPort#execute}）。
     *
     * <p>调用方（app 装配的本服务）须以外层事务包住「状态流转 + 库存过账」，使盘点流水与
     * 单据完成状态原子提交（拆解 §1.4）。全部行差异为 0 时无流水，仅推进状态。
     */
    @Audited(action = "stock_count.post", targetType = "stock_count")
    public StockCountDocument post(String docNo, String operator) {
        requireOperator(operator);
        StockCountDocument document = get(docNo);
        document.registerEventPublisher(eventPublisher);
        // 状态先推进到执行中（合法流转校验在 BusinessDocument），再过账库存
        document.startExecution(operator);
        List<StockMovementCommand> batch = buildMovements(document);
        if (!batch.isEmpty()) {
            inventory.execute(batch, operator);
        }
        document.complete(operator);
        repository.save(document);
        return document;
    }

    /** 红字盘点反向流水的合成关联引用（盘点无 GL 凭证、不复制业务单头，故用合成号关联） */
    static final String REVERSAL_REF_PREFIX = "REVERSAL:";

    /**
     * 冲销已完成盘点单（红字盘点单，M4-T07c）：按已固化的原成本<b>对称反向各非零差异行</b>，
     * 原单 COMPLETED → REVERSED。<b>盘点不出 GL 凭证</b>（库存账实调整，盈亏经存货成本体现，
     * 不在本单产生凭证），故本冲销只反向库存、不红冲凭证（设计真源 §77）。物理删除不存在
     * （CLAUDE.md 原则 2）。
     *
     * <p>同一外层事务内（由 {@code StocktakeService.reverse} 提供 @Transactional）执行：
     * <ol>
     *   <li>校验原单 COMPLETED（非则 {@link IllegalStateException}）、未被冲销（幂等：已 REVERSED 拒）；</li>
     *   <li>对每个非零差异行按原盘盈/盘亏成本对称反向，组成<b>一批</b>同事务原子过账
     *       （{@link InventoryPostingPort#execute}）：
     *     <ul>
     *       <li>原<b>盘盈</b>（COUNT_GAIN 入库）→ 反向 {@code COUNT_LOSS} 出库，{@code overriddenUnitCost}
     *           = 原 COUNT_GAIN 流水固化单价（按原盘盈单价精确出库，避重算移动加权失真），
     *           幂等键 {@code REVERSAL:SC号:行号}；</li>
     *       <li>原<b>盘亏</b>（COUNT_LOSS 出库）→ 反向 {@code COUNT_GAIN} 入库，单价 = 原 COUNT_LOSS
     *           流水固化成本（{@link InventoryPostingPort#originalUnitCost} 读回），按原盘亏成本回补，
     *           幂等键 {@code REVERSAL:SC号:行号}；</li>
     *       <li>原差异 == 0 的行无原流水，跳过（与过账口径对称）。</li>
     *     </ul>
     *     反向后该商品在该仓的数量与金额双双回到盘点前；</li>
     *   <li>原单 {@link BusinessDocument#reverse}（COMPLETED → REVERSED + 红字关联合成引用）。</li>
     * </ol>
     *
     * <p>反向幂等键加 {@code REVERSAL:} 前缀，避与原 {@code STOCK_COUNT:} 流水幂等键撞；原成本经
     * {@link InventoryPostingPort#originalUnitCost} 按原行幂等键读回（流水缺失/无单价 → 防御性
     * {@link IllegalStateException} → 整事务回滚）。
     *
     * @param docNo    被冲销的盘点单号（须 COMPLETED）
     * @param operator 操作人
     * @return 已转 REVERSED 的原盘点单
     */
    @Audited(action = "stock_count.reverse", targetType = "stock_count")
    public StockCountDocument reverse(String docNo, String operator) {
        requireOperator(operator);
        StockCountDocument document = get(docNo);
        if (document.getStatus() == DocumentStatus.REVERSED) {
            throw new IllegalStateException("盘点单[" + docNo + "] 已冲销，不可重复冲销");
        }
        if (document.getStatus() != DocumentStatus.COMPLETED) {
            throw new IllegalStateException("盘点单[" + docNo + "] 当前状态 " + document.getStatus()
                    + " 不可冲销（仅已过账的盘点单可冲销）");
        }
        document.registerEventPublisher(eventPublisher);
        // 按原盘盈/盘亏成本对称反向（一批同事务原子过账；全无差异行时无流水）
        List<StockMovementCommand> batch = buildReversalMovements(document);
        if (!batch.isEmpty()) {
            inventory.execute(batch, operator);
        }
        // 原单 COMPLETED → REVERSED + 红字关联（盘点无凭证/无红字单头，用合成引用）
        document.reverse(operator, REVERSAL_REF_PREFIX + docNo);
        repository.save(document);
        return document;
    }

    /** 按单据号查（不存在抛 {@link StockCountNotFoundException} → API 404） */
    public StockCountDocument get(String docNo) {
        return repository.findByDocNo(docNo)
                .orElseThrow(() -> new StockCountNotFoundException(docNo));
    }

    /** 分页查询 */
    public PageResult<StockCountDocument> search(StockCountQuery query) {
        return repository.search(Objects.requireNonNull(query, "query 不能为空"));
    }

    // ---------------------------------------------------------------
    // 过账口径（拆解 §1.6.1）：盘盈入库 / 盘亏出库 / 无差异跳过
    // ---------------------------------------------------------------

    /** 按各行差异组装库存移动指令（差异为 0 的行不产生流水） */
    private List<StockMovementCommand> buildMovements(StockCountDocument document) {
        List<StockMovementCommand> batch = new ArrayList<>();
        for (StockCountLine line : document.getLines()) {
            BigDecimal diff = line.diffQty();
            if (diff == null) {
                // 防御性：审核已校验全部录入，过账阶段不应出现未录入行
                throw new IllegalStateException("盘点单[" + document.getDocNo() + "] 行号 "
                        + line.getLineNo() + " 未录入实盘，不能过账");
            }
            int sign = diff.signum();
            if (sign == 0) {
                continue;
            }
            String key = idempotencyKey(document.getDocNo(), line.getLineNo());
            if (sign > 0) {
                batch.add(buildGain(document, line, diff, key));
            } else {
                batch.add(buildLoss(document, line, diff.negate(), key));
            }
        }
        return batch;
    }

    /**
     * 盘盈入库（COUNT_GAIN）：单价取「当前余额派生加权单价」；当前数量为 0 时必须用
     * 行的录入单价 enteredUnitCost，缺失则拒绝（拆解 §1.6.1）。
     */
    private InboundCommand buildGain(StockCountDocument document, StockCountLine line,
                                     BigDecimal gainQty, String key) {
        InventoryBalanceView balance = inventory.balanceOf(document.getWarehouseId(), line.getProductId());
        BigDecimal unitCost;
        if (balance.quantity().signum() > 0) {
            // 当前有存量：按当前派生加权单价入库（口径与库存盘盈默认一致）
            unitCost = balance.derivedUnitCost();
        } else {
            // 零库存盘盈：派生单价无从谈起，必须用录入单价
            unitCost = line.getEnteredUnitCost();
            if (unitCost == null) {
                throw new IllegalArgumentException("盘点单[" + document.getDocNo() + "] 行号 "
                        + line.getLineNo() + "（商品 " + line.getProductId()
                        + "）为零库存盘盈，必须录入盘盈单价 enteredUnitCost");
            }
        }
        return new InboundCommand(document.getWarehouseId(), line.getProductId(),
                InventoryTxnType.COUNT_GAIN, gainQty, unitCost, null,
                SRC_DOC_TYPE, document.getDocNo(), line.getLineNo(), key);
    }

    /** 盘亏出库（COUNT_LOSS）：成本由库存服务按移动加权自动算 */
    private OutboundCommand buildLoss(StockCountDocument document, StockCountLine line,
                                      BigDecimal lossQty, String key) {
        return new OutboundCommand(document.getWarehouseId(), line.getProductId(),
                InventoryTxnType.COUNT_LOSS, lossQty,
                SRC_DOC_TYPE, document.getDocNo(), line.getLineNo(), key);
    }

    /**
     * 按各行差异组装反向库存指令（M4-T07c 红冲），对称还原原盘点（差异为 0 的行无原流水、跳过）：
     * <ul>
     *   <li>原<b>盘盈</b>（diff &gt; 0，曾 COUNT_GAIN 入库）→ {@code COUNT_LOSS} 出库 |diff| 数量，
     *       {@code overriddenUnitCost} = 原 COUNT_GAIN 流水固化单价（{@link InventoryPostingPort#originalUnitCost}
     *       按原行幂等键读回），按原盘盈单价精确出库（避重算移动加权失真，设计真源 §1.6/§77）；</li>
     *   <li>原<b>盘亏</b>（diff &lt; 0，曾 COUNT_LOSS 出库）→ {@code COUNT_GAIN} 入库 |diff| 数量，
     *       单价 = 原 COUNT_LOSS 流水固化成本（同上读回），按原盘亏成本回补库存。</li>
     * </ul>
     * 反向后该商品该仓数量/金额双双回到盘点前；反向幂等键加 {@code REVERSAL:} 前缀避撞原流水。
     */
    private List<StockMovementCommand> buildReversalMovements(StockCountDocument document) {
        List<StockMovementCommand> batch = new ArrayList<>();
        for (StockCountLine line : document.getLines()) {
            BigDecimal diff = line.diffQty();
            if (diff == null) {
                // 防御性：已过账盘点单各行理应已录入实盘（过账已校验），此处不应出现
                throw new IllegalStateException("盘点单[" + document.getDocNo() + "] 行号 "
                        + line.getLineNo() + " 缺实盘数据，无法冲销");
            }
            int sign = diff.signum();
            if (sign == 0) {
                continue;
            }
            // 原盘点流水幂等键（红冲按原成本反向：读回该行原流水固化单价）
            String originalKey = idempotencyKey(document.getDocNo(), line.getLineNo());
            String reversalKey = reversalIdempotencyKey(document.getDocNo(), line.getLineNo());
            BigDecimal originalUnitCost = inventory.originalUnitCost(originalKey);
            if (sign > 0) {
                // 原盘盈 COUNT_GAIN → 反向 COUNT_LOSS 出库，按原盘盈单价精确反向
                batch.add(new OutboundCommand(document.getWarehouseId(), line.getProductId(),
                        InventoryTxnType.COUNT_LOSS, diff,
                        SRC_DOC_TYPE, document.getDocNo(), line.getLineNo(), reversalKey,
                        originalUnitCost));
            } else {
                // 原盘亏 COUNT_LOSS → 反向 COUNT_GAIN 入库，按原盘亏成本回补
                batch.add(new InboundCommand(document.getWarehouseId(), line.getProductId(),
                        InventoryTxnType.COUNT_GAIN, diff.negate(), originalUnitCost, null,
                        SRC_DOC_TYPE, document.getDocNo(), line.getLineNo(), reversalKey));
            }
        }
        return batch;
    }

    /** 幂等键约定 STOCK_COUNT:docNo:行号（拆解 §1.3） */
    private static String idempotencyKey(String docNo, int lineNo) {
        return SRC_DOC_TYPE + ":" + docNo + ":" + lineNo;
    }

    /** 红冲反向流水幂等键 REVERSAL:docNo:行号（M4-T07c，避与原 STOCK_COUNT 流水幂等键撞） */
    private static String reversalIdempotencyKey(String docNo, int lineNo) {
        return REVERSAL_REF_PREFIX + docNo + ":" + lineNo;
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }
}
