package com.sjherp.domain.purchase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.OutboundCommand;
import com.sjherp.domain.inventory.StockMovementCommand;

/**
 * 采购入库单领域服务（M3-T06，路线图 §5 采购线）。
 *
 * <p>所有采购入库写操作的唯一入口（CLAUDE.md 原则 1）。纯 Java 零依赖：依赖采购入库仓储端口
 * {@link PurchaseReceiptRepository}、采购订单领域服务 {@link PurchaseOrderService}（读引用单、
 * 过账时回写到货量——采购订单写仍只经其唯一入口）与库存能力端口 {@link InventoryPostingPort}，
 * 由 app 层装配并把单据状态变更 + 库存过账 + 采购订单到货量回写包进同一外层事务（拆解 §1.4）。
 *
 * <h2>建单：引用采购订单 + 部分收货校验（CLAUDE.md 原则 1）</h2>
 * 引用的采购订单必须已审核（APPROVED，未关闭才可继续收货）；各行引用有效的采购订单行，
 * 商品与采购订单行一致，本次收货数量 ≤ 该采购订单行<b>未收量</b>（同一收货单内多行引用同一
 * 采购订单行时按累计校验）。收货单价默认取采购订单行单价，建单输入可覆盖。
 *
 * <h2>过账口径（财务正确性核心）</h2>
 * 审核（APPROVED）后过账：单据 EXECUTING → COMPLETED，各行组一笔 {@code PURCHASE_IN} 入库指令
 * （unitCost = 收货单价），组成<b>一批</b>一次 {@link InventoryPostingPort#execute} 同事务原子过账
 * （幂等键 {@code PURCHASE_RECEIPT:PR-xxx:行号}）；同事务调 {@link PurchaseOrderService#applyReceipt}
 * 把各行收货量按引用关系回写到采购订单行的 receivedQty（部分收货跟踪）。库存入账与到货量回写
 * 原子提交，绝不出现「库存进了、订单到货量没更新」的破碎状态。
 *
 * <p>退货（负向收货）走冲销语义，见 {@link #reverse} TODO（M4-T07 统一做）。
 */
public class PurchaseReceiptService {

    /** 库存流水来源单据类型（与拆解 §1.2 src_doc_type 约定一致） */
    static final String SRC_DOC_TYPE = "PURCHASE_RECEIPT";

    private final PurchaseReceiptRepository repository;
    private final PurchaseOrderService purchaseOrderService;
    private final InventoryPostingPort inventory;

    /** 领域事件发布器：状态流转经它自动落 document.status_changed 审计 */
    private final DomainEventPublisher eventPublisher;

    public PurchaseReceiptService(PurchaseReceiptRepository repository,
                                  PurchaseOrderService purchaseOrderService,
                                  InventoryPostingPort inventory,
                                  DomainEventPublisher eventPublisher) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.purchaseOrderService = Objects.requireNonNull(purchaseOrderService,
                "purchaseOrderService 不能为空");
        this.inventory = Objects.requireNonNull(inventory, "inventory 不能为空");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher 不能为空");
    }

    /**
     * 创建采购入库单（草稿）：引用某采购订单收货，支持部分收货。
     *
     * @param docNo           单据号（PR-年月-序号，app 层用 DocumentNumberGenerator 生成）
     * @param purchaseOrderNo 引用的采购订单号（必须 APPROVED）
     * @param warehouseId     收货仓库 id（存在性/启用校验在 app 入口层）
     * @param receiptDate     收货日期
     * @param remark          收货说明（可空）
     * @param lines           行输入（引用采购订单行 + 收货数量 + 可选收货单价）
     * @param operator        操作人
     */
    @Audited(action = "purchase_receipt.create", targetType = "purchase_receipt")
    public PurchaseReceipt create(String docNo, String purchaseOrderNo, long warehouseId,
                                  LocalDate receiptDate, String remark,
                                  List<PurchaseReceiptLineInput> lines, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(docNo, "单据号不能为空");
        Objects.requireNonNull(purchaseOrderNo, "引用的采购订单号不能为空");
        Objects.requireNonNull(lines, "采购入库单行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("采购入库单至少要有一行");
        }

        PurchaseOrder order = purchaseOrderService.get(purchaseOrderNo);
        if (order.getStatus() != DocumentStatus.APPROVED) {
            throw new IllegalArgumentException("采购订单[" + purchaseOrderNo + "] 当前状态 "
                    + order.getStatus() + " 不可收货（仅已审核且未关闭的订单可收货）");
        }
        Map<Integer, PurchaseOrderLine> poLineByNo = new HashMap<>();
        for (PurchaseOrderLine poLine : order.getLines()) {
            poLineByNo.put(poLine.getLineNo(), poLine);
        }

        // 同一收货单内多行引用同一采购订单行时，按累计校验「不超过未收量」
        Map<Integer, BigDecimal> takenByPoLine = new HashMap<>();
        List<PurchaseReceiptLine> domainLines = new ArrayList<>(lines.size());
        int lineNo = 1;
        for (PurchaseReceiptLineInput input : lines) {
            PurchaseOrderLine poLine = poLineByNo.get(input.poLineNo());
            if (poLine == null) {
                throw new IllegalArgumentException("采购订单[" + purchaseOrderNo + "] 不存在行号 "
                        + input.poLineNo());
            }
            BigDecimal qty = input.quantity();
            if (qty == null || qty.signum() <= 0) {
                throw new IllegalArgumentException("收货数量必须大于 0: 引用采购订单行 " + input.poLineNo());
            }
            BigDecimal alreadyTaken = takenByPoLine.getOrDefault(input.poLineNo(), BigDecimal.ZERO);
            BigDecimal outstanding = poLine.outstandingQty().subtract(alreadyTaken);
            if (qty.compareTo(outstanding) > 0) {
                throw new IllegalArgumentException("收货数量 " + qty.toPlainString()
                        + " 超过采购订单行 " + input.poLineNo() + " 未收量 " + outstanding.toPlainString());
            }
            takenByPoLine.put(input.poLineNo(), alreadyTaken.add(qty));
            // 收货单价默认取采购订单行单价，建单输入可覆盖
            BigDecimal unitCost = input.unitCost() != null ? input.unitCost() : poLine.getUnitPrice();
            domainLines.add(PurchaseReceiptLine.create(lineNo++, input.poLineNo(),
                    poLine.getProductId(), qty, unitCost));
        }

        PurchaseReceipt receipt = PurchaseReceipt.create(docNo, purchaseOrderNo, warehouseId,
                receiptDate, remark, domainLines, operator);
        receipt.registerEventPublisher(eventPublisher);
        repository.save(receipt);
        return receipt;
    }

    /** 审核采购入库单：DRAFT → APPROVED（业务内容自此锁定）。 */
    @Audited(action = "purchase_receipt.approve", targetType = "purchase_receipt")
    public PurchaseReceipt approve(String docNo, String operator) {
        requireOperator(operator);
        PurchaseReceipt receipt = get(docNo);
        receipt.registerEventPublisher(eventPublisher);
        receipt.approve(operator);
        repository.save(receipt);
        return receipt;
    }

    /**
     * 过账采购入库单：APPROVED → EXECUTING → COMPLETED。
     *
     * <p>各行组一笔 PURCHASE_IN 入库指令（unitCost = 收货单价），一批同事务原子过账经库存唯一
     * 写入口；同事务把各行收货量回写到采购订单行（{@link PurchaseOrderService#applyReceipt}）。
     * 调用方（app 装配的本服务）须以外层事务包住「状态流转 + 库存过账 + 到货量回写」。
     */
    @Audited(action = "purchase_receipt.post", targetType = "purchase_receipt")
    public PurchaseReceipt post(String docNo, String operator) {
        requireOperator(operator);
        PurchaseReceipt receipt = get(docNo);
        receipt.registerEventPublisher(eventPublisher);
        // 状态先推进到执行中（合法流转校验在 BusinessDocument），再过账库存
        receipt.startExecution(operator);
        List<StockMovementCommand> batch = buildMovements(receipt);
        inventory.execute(batch, operator);
        // 同事务回写采购订单各行到货量（采购订单写仍只经其唯一入口）
        purchaseOrderService.applyReceipt(receipt.getPurchaseOrderNo(),
                buildReceivedLines(receipt), operator);
        receipt.complete(operator);
        repository.save(receipt);
        return receipt;
    }

    /**
     * 冲销已完成采购入库单（退货红字单，M4-T07b）：反向库存 + 回滚采购订单到货量 + 原单转 REVERSED。
     *
     * <p>同一外层事务内（由 {@code PurchaseReceiptAppService.reverse} 提供）执行：
     * <ol>
     *   <li>校验原单 COMPLETED（非则 {@link IllegalStateException}）、未被冲销（幂等：已 REVERSED 拒）；</li>
     *   <li>各行组一笔 {@code SALES_OUT} 反向出库指令，<b>按已固化的原收货单价反向</b>
     *       （{@code overriddenUnitCost = 原行 unitCost}，跳过移动加权——期间可能已进新货重算会失真，
     *       设计真源 §1.6/§2 共享基元 1），幂等键 {@code REVERSAL:<PR号>:<行号>}，经库存唯一写入口；</li>
     *   <li>同事务调 {@link PurchaseOrderService#reverseReceipt} 把各行到货量从采购订单行回退；</li>
     *   <li>原单 {@link BusinessDocument#reverse}（COMPLETED → REVERSED + 红字关联 {@code reversalDocNo}）。</li>
     * </ol>
     *
     * <p>红字凭证（红冲采购入库自动凭证）由 app 层 {@code PurchaseReceiptAppService.reverse} 在调用本方法
     * <b>之前</b>经 {@code VoucherAppService.reverse} 生成，红字凭证号作为本方法的 {@code reversalDocNo}
     * 传入（保证原单 reversedById 指向红字凭证、冲销链路可审计）。物理删除不存在（CLAUDE.md 原则 2）。
     *
     * @param docNo         被冲销的采购入库单号（须 COMPLETED）
     * @param reversalDocNo 红字关联单据号（红字凭证号，由 app 层冲销自动凭证后回传）
     * @param operator      操作人
     * @return 已转 REVERSED 的原采购入库单
     */
    @Audited(action = "purchase_receipt.reverse", targetType = "purchase_receipt")
    public PurchaseReceipt reverse(String docNo, String reversalDocNo, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(reversalDocNo, "红字关联单据号不能为空");
        PurchaseReceipt receipt = get(docNo);
        if (receipt.getStatus() == DocumentStatus.REVERSED) {
            throw new IllegalStateException("采购入库单[" + docNo + "] 已冲销，不可重复冲销");
        }
        if (receipt.getStatus() != DocumentStatus.COMPLETED) {
            throw new IllegalStateException("采购入库单[" + docNo + "] 当前状态 " + receipt.getStatus()
                    + " 不可冲销（仅已过账的入库单可冲销）");
        }
        receipt.registerEventPublisher(eventPublisher);
        // 反向库存：各行按原收货单价反向出库（SALES_OUT 出库方向，overriddenUnitCost=原单价）
        List<StockMovementCommand> batch = buildReversalMovements(receipt);
        inventory.execute(batch, operator);
        // 同事务回退采购订单各行到货量（采购订单写仍只经其唯一入口）
        purchaseOrderService.reverseReceipt(receipt.getPurchaseOrderNo(),
                buildReceivedLines(receipt), operator);
        // 原单 COMPLETED → REVERSED + 红字关联
        receipt.reverse(operator, reversalDocNo);
        repository.save(receipt);
        return receipt;
    }

    /**
     * 回写收货行累计已开票量（采购发票过账时由 {@link PurchaseInvoiceService} 在同一外层事务内调用，
     * 与应付生成原子提交）。守门「累计开票量 ≤ 收货量」（超量抛领域异常），防跨发票超额开票虚增应付
     * （CLAUDE.md 原则 2）。
     *
     * <p>不单独标 @Audited（不是独立用户动作，随发票 post 审计覆盖），故无 operator 参数——口径与
     * {@link com.sjherp.domain.sales.SalesOrderService#recordDelivery} 一致。
     *
     * @param docNo    被引用的采购入库单号（必须 COMPLETED）
     * @param invoiced 各行本次开票量（收货行号 → 开票数量，由发票按引用关系组装）
     */
    public void recordInvoiced(String docNo, List<InvoicedLine> invoiced) {
        Objects.requireNonNull(invoiced, "开票回写行不能为空");
        PurchaseReceipt receipt = get(docNo);
        for (InvoicedLine line : invoiced) {
            receipt.invoiceLine(line.lineNo(), line.quantity());
        }
        repository.save(receipt);
    }

    /**
     * 回滚收货行累计已开票量（M4-T07b 采购发票红冲时由红冲编排在同一外层事务内调用，与应付冲回原子提交）。
     * 与 {@link #recordInvoiced} 对称：守门「回滚后已开票量 ≥ 0」（下溢抛领域异常）。
     *
     * <p>不单独标 @Audited（口径同 {@link #recordInvoiced}，随发票红冲审计覆盖），故无 operator 参数。
     *
     * @param docNo    被引用的采购入库单号（必须 COMPLETED）
     * @param invoiced 各行本次回滚开票量（收货行号 → 回滚数量，由发票红冲按引用关系组装）
     */
    public void reverseInvoiced(String docNo, List<InvoicedLine> invoiced) {
        Objects.requireNonNull(invoiced, "回滚开票行不能为空");
        PurchaseReceipt receipt = get(docNo);
        for (InvoicedLine line : invoiced) {
            receipt.reverseInvoiceLine(line.lineNo(), line.quantity());
        }
        repository.save(receipt);
    }

    /** 按单据号查（不存在抛 {@link PurchaseReceiptNotFoundException} → API 404） */
    public PurchaseReceipt get(String docNo) {
        return repository.findByDocNo(docNo)
                .orElseThrow(() -> new PurchaseReceiptNotFoundException(docNo));
    }

    /** 分页查询 */
    public PageResult<PurchaseReceipt> search(PurchaseReceiptQuery query) {
        return repository.search(Objects.requireNonNull(query, "query 不能为空"));
    }

    // ---------------------------------------------------------------
    // 过账口径：各行一笔 PURCHASE_IN（unitCost=收货单价）+ 回写到货量
    // ---------------------------------------------------------------

    /** 按各行组装入库指令：PURCHASE_IN，unitCost = 收货单价（必填，拆解 §1.6.1 入库口径） */
    private List<StockMovementCommand> buildMovements(PurchaseReceipt receipt) {
        List<StockMovementCommand> batch = new ArrayList<>(receipt.getLines().size());
        for (PurchaseReceiptLine line : receipt.getLines()) {
            String key = idempotencyKey(receipt.getDocNo(), line.getLineNo());
            batch.add(new InboundCommand(receipt.getWarehouseId(), line.getProductId(),
                    InventoryTxnType.PURCHASE_IN, line.getQuantity(), line.getUnitCost(), null,
                    SRC_DOC_TYPE, receipt.getDocNo(), line.getLineNo(), key));
        }
        return batch;
    }

    /**
     * 按各行组装反向出库指令（M4-T07b 红冲）：{@code SALES_OUT} 出库方向、数量=原收货量、
     * {@code overriddenUnitCost = 原收货单价}（按已固化原单价反向，跳过移动加权），
     * 幂等键 {@code REVERSAL:PR-xxx:行号}（避与原 PURCHASE_IN 流水幂等键撞）。
     */
    private List<StockMovementCommand> buildReversalMovements(PurchaseReceipt receipt) {
        List<StockMovementCommand> batch = new ArrayList<>(receipt.getLines().size());
        for (PurchaseReceiptLine line : receipt.getLines()) {
            String key = reversalIdempotencyKey(receipt.getDocNo(), line.getLineNo());
            batch.add(new OutboundCommand(receipt.getWarehouseId(), line.getProductId(),
                    InventoryTxnType.SALES_OUT, line.getQuantity(), SRC_DOC_TYPE, receipt.getDocNo(),
                    line.getLineNo(), key, line.getUnitCost()));
        }
        return batch;
    }

    /** 收货量回写：收货行的引用采购订单行号 → 收货数量 */
    private List<PurchaseOrderService.ReceivedLine> buildReceivedLines(PurchaseReceipt receipt) {
        List<PurchaseOrderService.ReceivedLine> received = new ArrayList<>(receipt.getLines().size());
        for (PurchaseReceiptLine line : receipt.getLines()) {
            received.add(new PurchaseOrderService.ReceivedLine(line.getPoLineNo(), line.getQuantity()));
        }
        return received;
    }

    /** 幂等键约定 PURCHASE_RECEIPT:docNo:行号（拆解 §1.3） */
    private static String idempotencyKey(String docNo, int lineNo) {
        return SRC_DOC_TYPE + ":" + docNo + ":" + lineNo;
    }

    /** 红冲反向出库幂等键 REVERSAL:docNo:行号（M4-T07b，避与原 PURCHASE_IN 流水幂等键撞） */
    private static String reversalIdempotencyKey(String docNo, int lineNo) {
        return "REVERSAL:" + docNo + ":" + lineNo;
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }

    /** 开票回写的单行：采购入库单行号 → 本次开票数量（M3-T07 发票过账组装） */
    public record InvoicedLine(int lineNo, BigDecimal quantity) {
    }
}
