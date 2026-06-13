package com.sjherp.domain.transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.OutboundCommand;
import com.sjherp.domain.inventory.StockMovementCommand;

/**
 * 库存调拨单领域服务（M3-T04，拆解 docs/M3拆解-库存与成本.md §1.6.5 调拨成本守恒）。
 *
 * <p>所有调拨写操作的唯一入口（CLAUDE.md 原则 1）。纯 Java 零依赖：依赖调拨仓储端口
 * {@link TransferRepository} 与库存能力端口 {@link InventoryPostingPort}，
 * 由 app 层装配并把单据状态变更 + 库存过账包进同一外层事务（@Transactional，拆解 §1.4）。
 *
 * <h2>过账口径（财务正确性核心，拆解 §1.6.5）</h2>
 * 审核（APPROVED）后过账：单据 EXECUTING → COMPLETED，对每行拆成两腿组成<b>一个</b>
 * {@link StockMovementCommand} 列表，一次 {@link InventoryPostingPort#execute} 同事务原子过账：
 * <ol>
 *   <li><b>调出腿</b>：{@code OUTBOUND TRANSFER_OUT} 从调出仓出库，成本由库存服务按移动加权
 *       自动算（幂等键 {@code TRANSFER:TR-xxx:行号:OUT}）；</li>
 *   <li><b>调入腿</b>：{@code INBOUND TRANSFER_IN} 入调入仓，<b>不指定单价</b>——其
 *       {@link InboundCommand#transferOutKey() transferOutKey} 设为调出腿的幂等键，
 *       库存服务在同批 execute 内用调出腿成本作调入成本（幂等键 {@code TRANSFER:TR-xxx:行号:IN}）。</li>
 * </ol>
 * 这样「调出仓减少的金额 == 调入仓增加的金额」严格成立，整个企业的库存价值不因调拨凭空增减
 * （金额守恒，CLAUDE.md 原则 2 财务专业性）。两腿必须在同一批 execute 内，使库存服务能从本批
 * 结果取到调出腿成本（同批结果优先于查已落库流水），并整体原子提交。
 *
 * <p>调出腿在批次中<b>排在调入腿之前</b>：execute 按指令顺序逐条过账，调入腿过账时调出腿已在
 * 本批结果中，库存服务即可读取其成本。
 *
 * <p>已 COMPLETED 单据的冲销（红字调拨单）见 {@link #reverse} TODO（M4 统一做）。
 */
public class TransferService {

    /** 库存流水来源单据类型（与拆解 §1.2 src_doc_type 约定一致） */
    static final String SRC_DOC_TYPE = "TRANSFER";

    private final TransferRepository repository;
    private final InventoryPostingPort inventory;

    /** 领域事件发布器：状态流转经它自动落 document.status_changed 审计（app 装配 SyncDomainEventPublisher） */
    private final DomainEventPublisher eventPublisher;

    public TransferService(TransferRepository repository, InventoryPostingPort inventory,
                           DomainEventPublisher eventPublisher) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.inventory = Objects.requireNonNull(inventory, "inventory 不能为空");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher 不能为空");
    }

    /**
     * 创建调拨单（草稿）：行集合由 app 入口层组装后传入（商品 + 调拨数量）。
     *
     * @param docNo           单据号（TR-年月-序号，app 层用 DocumentNumberGenerator 生成）
     * @param fromWarehouseId 调出仓库 id（存在性/启用校验在 app 入口层）
     * @param toWarehouseId   调入仓库 id（必须 ≠ 调出仓）
     * @param remark          调拨说明（可空）
     * @param lines           行输入（商品 + 调拨数量）
     * @param operator        操作人
     */
    @Audited(action = "stock_transfer.create", targetType = "stock_transfer")
    public TransferDocument create(String docNo, long fromWarehouseId, long toWarehouseId,
                                   String remark, List<TransferLineInput> lines, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(docNo, "单据号不能为空");
        Objects.requireNonNull(lines, "调拨行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("调拨单至少要有一行");
        }
        List<TransferLine> domainLines = new ArrayList<>(lines.size());
        int lineNo = 1;
        for (TransferLineInput input : lines) {
            domainLines.add(TransferLine.create(lineNo++, input.productId(), input.quantity()));
        }
        TransferDocument document = TransferDocument.create(docNo, fromWarehouseId, toWarehouseId,
                remark, domainLines, operator);
        document.registerEventPublisher(eventPublisher);
        repository.save(document);
        return document;
    }

    /** 审核调拨单：DRAFT → APPROVED（业务内容自此锁定）。 */
    @Audited(action = "stock_transfer.approve", targetType = "stock_transfer")
    public TransferDocument approve(String docNo, String operator) {
        requireOperator(operator);
        TransferDocument document = get(docNo);
        document.registerEventPublisher(eventPublisher);
        document.approve(operator);
        repository.save(document);
        return document;
    }

    /**
     * 过账调拨单：APPROVED → EXECUTING → COMPLETED，对各行按 §1.6.5 口径生成「调出腿 + 调入腿」，
     * 组成一批同事务原子过账（{@link InventoryPostingPort#execute}）。
     *
     * <p>调用方（app 装配的本服务）须以外层事务包住「状态流转 + 库存过账」，使两腿流水与单据完成状态
     * 原子提交（拆解 §1.4）。
     */
    @Audited(action = "stock_transfer.post", targetType = "stock_transfer")
    public TransferDocument post(String docNo, String operator) {
        requireOperator(operator);
        TransferDocument document = get(docNo);
        document.registerEventPublisher(eventPublisher);
        // 状态先推进到执行中（合法流转校验在 BusinessDocument），再过账库存
        document.startExecution(operator);
        List<StockMovementCommand> batch = buildMovements(document);
        inventory.execute(batch, operator);
        document.complete(operator);
        repository.save(document);
        return document;
    }

    /**
     * 冲销已完成调拨单（红字调拨单）。
     *
     * <p>TODO（M4 统一做）：生成反向调拨单驱动反向两腿流水（原调入仓出库、原调出仓入库），
     * 原单 COMPLETED → REVERSED 并红字关联。当前未实现——调拨流水的纠错暂走反向调拨单，
     * 调拨单本身不提供物理删除（CLAUDE.md 原则 2：只可冲销不可删除）。
     */
    @Audited(action = "stock_transfer.reverse", targetType = "stock_transfer")
    public TransferDocument reverse(String docNo, String operator) {
        requireOperator(operator);
        throw new UnsupportedOperationException(
                "调拨单冲销（红字调拨单）尚未实现，统一在 M4 落地");
    }

    /** 按单据号查（不存在抛 {@link TransferNotFoundException} → API 404） */
    public TransferDocument get(String docNo) {
        return repository.findByDocNo(docNo)
                .orElseThrow(() -> new TransferNotFoundException(docNo));
    }

    /** 分页查询 */
    public PageResult<TransferDocument> search(TransferQuery query) {
        return repository.search(Objects.requireNonNull(query, "query 不能为空"));
    }

    // ---------------------------------------------------------------
    // 过账口径（拆解 §1.6.5）：每行两腿，调出在前、调入用调出原值入库（金额守恒）
    // ---------------------------------------------------------------

    /** 按各行组装库存移动指令：调出腿（TRANSFER_OUT）在前、调入腿（TRANSFER_IN）紧随其后 */
    private List<StockMovementCommand> buildMovements(TransferDocument document) {
        List<StockMovementCommand> batch = new ArrayList<>(document.getLines().size() * 2);
        for (TransferLine line : document.getLines()) {
            String outKey = idempotencyKey(document.getDocNo(), line.getLineNo(), "OUT");
            String inKey = idempotencyKey(document.getDocNo(), line.getLineNo(), "IN");
            // ① 调出腿：从调出仓出库，成本由库存服务按移动加权自动算
            batch.add(new OutboundCommand(document.getFromWarehouseId(), line.getProductId(),
                    InventoryTxnType.TRANSFER_OUT, line.getQuantity(),
                    SRC_DOC_TYPE, document.getDocNo(), line.getLineNo(), outKey));
            // ② 调入腿：入调入仓，不指定单价；transferOutKey 关联调出腿，库存服务取调出腿成本作调入成本（金额守恒）
            batch.add(new InboundCommand(document.getToWarehouseId(), line.getProductId(),
                    InventoryTxnType.TRANSFER_IN, line.getQuantity(), null, outKey,
                    SRC_DOC_TYPE, document.getDocNo(), line.getLineNo(), inKey));
        }
        return batch;
    }

    /** 幂等键约定 TRANSFER:docNo:行号:腿（OUT/IN，拆解 §1.3/§1.6.5） */
    private static String idempotencyKey(String docNo, int lineNo, String leg) {
        return SRC_DOC_TYPE + ":" + docNo + ":" + lineNo + ":" + leg;
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }
}
