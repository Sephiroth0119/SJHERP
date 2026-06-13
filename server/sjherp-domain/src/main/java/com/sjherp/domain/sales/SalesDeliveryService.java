package com.sjherp.domain.sales;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.OutboundCommand;
import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;

/**
 * 销售出库单领域服务（M3-T09，路线图 §5 销售线）。
 *
 * <p>所有出库写操作的唯一入口（CLAUDE.md 原则 1）。纯 Java 零依赖：依赖出库仓储端口
 * {@link SalesDeliveryRepository}、销售订单服务 {@link SalesOrderService}（取订单做发货校验
 * 与回写累计发货量）、库存能力端口 {@link InventoryPostingPort}，由 app 层装配并把
 * 单据状态变更 + 库存过账 + 回写订单累计发货量包进同一外层事务（@Transactional，拆解 §1.4）。
 *
 * <h2>建单校验（拆解口径）</h2>
 * <ul>
 *   <li>引用的销售订单必须存在且<b>已审核（APPROVED/EXECUTING）</b>——草稿订单不能发货；</li>
 *   <li>每个出库行的关联订单行必须存在、商品一致；</li>
 *   <li>单行发货数量 ≤ 订单行剩余可发量（{@link SalesOrderLine#remainingQty()}），超发拒绝
 *       （同一出库单内多行命中同一订单行时按累计校验）。</li>
 * </ul>
 *
 * <h2>过账口径（COGS 与库存，财务核心）</h2>
 * 审核（APPROVED）后过账：单据 EXECUTING → COMPLETED，对每行生成一条
 * {@code OUTBOUND SALES_OUT}（成本由库存服务按移动加权自动算），组成<b>一批</b>一次
 * {@link InventoryPostingPort#execute} 同事务原子过账。幂等键 {@code SALES_DELIVERY:SD-xxx:行号}。
 * 过账后：
 * <ol>
 *   <li><b>把每行 COGS 记到出库行</b>：取 {@link StockMovementResult#totalCost()} 的正数口径
 *       （出库 totalCost 为负，取负号转正）回填到 {@link SalesDeliveryLine#assignCogs}，供 M4 利润核算；</li>
 *   <li><b>回写销售订单累计发货量</b>：{@link SalesOrderService#recordDelivery} 累加该订单行已发量。</li>
 * </ol>
 * 库存不足且负库存关闭（默认）时库存服务抛
 * {@link com.sjherp.domain.inventory.InsufficientStockException}，整批回滚（销售出库强校验库存）。
 *
 * <p>退货留 TODO（M4 统一做红字出库单）。
 */
public class SalesDeliveryService {

    /** 库存流水来源单据类型（与拆解 §1.2 src_doc_type 约定一致） */
    static final String SRC_DOC_TYPE = "SALES_DELIVERY";

    private final SalesDeliveryRepository repository;
    private final SalesOrderService salesOrderService;
    private final InventoryPostingPort inventory;

    /** 领域事件发布器：状态流转经它自动落 document.status_changed 审计（app 装配 SyncDomainEventPublisher） */
    private final DomainEventPublisher eventPublisher;

    public SalesDeliveryService(SalesDeliveryRepository repository, SalesOrderService salesOrderService,
                                InventoryPostingPort inventory, DomainEventPublisher eventPublisher) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.salesOrderService = Objects.requireNonNull(salesOrderService, "salesOrderService 不能为空");
        this.inventory = Objects.requireNonNull(inventory, "inventory 不能为空");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher 不能为空");
    }

    /**
     * 创建销售出库单（草稿）：引用某已审核销售订单做部分发货。
     *
     * @param docNo        单据号（SD-年月-序号，app 层用 DocumentNumberGenerator 生成）
     * @param salesOrderNo 引用的销售订单号（必须存在且已审核）
     * @param warehouseId  出库仓库 id（存在性/启用校验在 app 入口层）
     * @param remark       出库说明（可空）
     * @param lines        行输入（关联订单行号 + 商品 + 发货数量）
     * @param operator     操作人
     */
    @Audited(action = "sales_delivery.create", targetType = "sales_delivery")
    public SalesDelivery create(String docNo, String salesOrderNo, long warehouseId, String remark,
                                List<SalesDeliveryLineInput> lines, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(docNo, "单据号不能为空");
        Objects.requireNonNull(salesOrderNo, "关联销售订单号不能为空");
        Objects.requireNonNull(lines, "出库行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("销售出库单至少要有一行");
        }
        // 关联订单必须存在且已审核（草稿不可发货）
        SalesOrder order = salesOrderService.get(salesOrderNo);
        requireOrderDeliverable(order);

        // 同单内对同一订单行的发货累计校验剩余可发量（防一单多行合计超发）
        Map<Integer, java.math.BigDecimal> requestedBySoLine = new LinkedHashMap<>();
        List<SalesDeliveryLine> domainLines = new ArrayList<>(lines.size());
        int lineNo = 1;
        for (SalesDeliveryLineInput input : lines) {
            SalesOrderLine soLine = order.lineByNo(input.soLineNo());
            if (soLine.getProductId() != input.productId()) {
                throw new IllegalArgumentException("出库行商品（" + input.productId()
                        + "）与订单行 " + input.soLineNo() + " 商品（" + soLine.getProductId() + "）不一致");
            }
            SalesDeliveryLine deliveryLine = SalesDeliveryLine.create(
                    lineNo++, input.soLineNo(), input.productId(), input.quantity());
            java.math.BigDecimal cumulative = requestedBySoLine
                    .getOrDefault(input.soLineNo(), java.math.BigDecimal.ZERO)
                    .add(deliveryLine.getQuantity());
            if (cumulative.compareTo(soLine.remainingQty()) > 0) {
                throw new IllegalArgumentException("订单行 " + input.soLineNo() + "（商品 "
                        + soLine.getProductId() + "）本单累计发货 " + cumulative.toPlainString()
                        + " 超过剩余可发量 " + soLine.remainingQty().toPlainString());
            }
            requestedBySoLine.put(input.soLineNo(), cumulative);
            domainLines.add(deliveryLine);
        }

        SalesDelivery delivery = SalesDelivery.create(docNo, salesOrderNo, warehouseId, remark,
                domainLines, operator);
        delivery.registerEventPublisher(eventPublisher);
        repository.save(delivery);
        return delivery;
    }

    /** 审核出库单：DRAFT → APPROVED（业务内容自此锁定）。 */
    @Audited(action = "sales_delivery.approve", targetType = "sales_delivery")
    public SalesDelivery approve(String docNo, String operator) {
        requireOperator(operator);
        SalesDelivery delivery = get(docNo);
        delivery.registerEventPublisher(eventPublisher);
        delivery.approve(operator);
        repository.save(delivery);
        return delivery;
    }

    /** 作废出库单：仅 DRAFT 可作废（未产生任何库存影响）。 */
    @Audited(action = "sales_delivery.cancel", targetType = "sales_delivery")
    public SalesDelivery cancel(String docNo, String operator) {
        requireOperator(operator);
        SalesDelivery delivery = get(docNo);
        delivery.registerEventPublisher(eventPublisher);
        delivery.cancel(operator);
        repository.save(delivery);
        return delivery;
    }

    /**
     * 过账出库单：APPROVED → EXECUTING → COMPLETED，每行 SALES_OUT 组一批同事务原子过账
     * （{@link InventoryPostingPort#execute}）。过账后把每行 COGS 回填出库行 + 回写订单累计发货量。
     *
     * <p>调用方（app 装配的本服务）须以外层事务包住「状态流转 + 库存过账 + 回写订单」，
     * 使三者原子提交（拆解 §1.4）。库存不足且负库存关闭时整批回滚（销售出库强校验库存）。
     */
    @Audited(action = "sales_delivery.post", targetType = "sales_delivery")
    public SalesDelivery post(String docNo, String operator) {
        requireOperator(operator);
        SalesDelivery delivery = get(docNo);
        delivery.registerEventPublisher(eventPublisher);
        // 状态先推进到执行中（合法流转校验在 BusinessDocument），再过账库存
        delivery.startExecution(operator);

        // ① 组批：每行 SALES_OUT（成本由库存服务按移动加权自动算），幂等键 SALES_DELIVERY:docNo:行号
        List<StockMovementCommand> batch = new ArrayList<>(delivery.getLines().size());
        for (SalesDeliveryLine line : delivery.getLines()) {
            batch.add(new OutboundCommand(delivery.getWarehouseId(), line.getProductId(),
                    InventoryTxnType.SALES_OUT, line.getQuantity(),
                    SRC_DOC_TYPE, delivery.getDocNo(), line.getLineNo(),
                    idempotencyKey(delivery.getDocNo(), line.getLineNo())));
        }
        // ② 一次原子过账（库存不足整批回滚）；结果按行号映射回出库行
        List<StockMovementResult> results = inventory.execute(batch, operator);
        Map<Integer, StockMovementResult> byLineNo = new LinkedHashMap<>();
        for (StockMovementResult result : results) {
            byLineNo.put(result.srcLineNo(), result);
        }

        // ③ 把每行 COGS（出库 totalCost 为负，取正数口径）记到出库行 + 回写订单累计发货量
        for (SalesDeliveryLine line : delivery.getLines()) {
            StockMovementResult result = byLineNo.get(line.getLineNo());
            if (result == null) {
                throw new IllegalStateException("出库单[" + delivery.getDocNo() + "] 行号 "
                        + line.getLineNo() + " 未取得库存过账结果（COGS 无从回填）");
            }
            line.assignCogs(result.totalCost().negate());
            salesOrderService.recordDelivery(delivery.getSalesOrderNo(), line.getSoLineNo(),
                    line.getQuantity());
        }

        delivery.complete(operator);
        repository.save(delivery);
        return delivery;
    }

    /**
     * 冲销已完成出库单（红字出库单 / 退货）。
     *
     * <p>TODO（M4 统一做）：生成反向出库单驱动反向流水（原仓入库回冲）、回退订单累计发货量，
     * 原单 COMPLETED → REVERSED 并红字关联。当前未实现——退货暂走后续流程，出库单本身不提供
     * 物理删除（CLAUDE.md 原则 2：只可冲销不可删除）。
     */
    @Audited(action = "sales_delivery.reverse", targetType = "sales_delivery")
    public SalesDelivery reverse(String docNo, String operator) {
        requireOperator(operator);
        throw new UnsupportedOperationException(
                "销售出库单冲销（退货 / 红字出库单）尚未实现，统一在 M4 落地");
    }

    /**
     * 回写出库行累计已开票量（销售发票过账时由 {@link SalesInvoiceService} 在同一外层事务内调用，
     * 与应收挂账原子提交）。守门「累计开票量 ≤ 发货量」（超量抛领域异常），防跨发票超额开票虚增应收
     * （CLAUDE.md 原则 2）。
     *
     * <p>不单独标 @Audited（不是独立用户动作，随发票 post 审计覆盖），故无 operator 参数——口径与
     * {@link SalesOrderService#recordDelivery} 一致。
     *
     * @param docNo    被引用的销售出库单号（必须 COMPLETED）
     * @param invoiced 各行本次开票量（出库行号 → 开票数量，由发票按引用关系组装）
     */
    public void recordInvoiced(String docNo, List<InvoicedLine> invoiced) {
        Objects.requireNonNull(invoiced, "开票回写行不能为空");
        SalesDelivery delivery = get(docNo);
        for (InvoicedLine line : invoiced) {
            delivery.invoiceLine(line.lineNo(), line.quantity());
        }
        repository.save(delivery);
    }

    /** 按单据号查（不存在抛 {@link SalesDeliveryNotFoundException} → API 404） */
    public SalesDelivery get(String docNo) {
        return repository.findByDocNo(docNo)
                .orElseThrow(() -> new SalesDeliveryNotFoundException(docNo));
    }

    /** 分页查询 */
    public PageResult<SalesDelivery> search(SalesDeliveryQuery query) {
        return repository.search(Objects.requireNonNull(query, "query 不能为空"));
    }

    // ---------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------

    private static void requireOrderDeliverable(SalesOrder order) {
        DocumentStatus status = order.getStatus();
        if (status != DocumentStatus.APPROVED && status != DocumentStatus.EXECUTING) {
            throw new IllegalArgumentException("销售订单[" + order.getDocNo() + "] 当前状态 " + status
                    + " 不可发货（需先审核）");
        }
    }

    /** 幂等键约定 SALES_DELIVERY:docNo:行号（拆解 §1.3） */
    private static String idempotencyKey(String docNo, int lineNo) {
        return SRC_DOC_TYPE + ":" + docNo + ":" + lineNo;
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }

    /** 开票回写的单行：销售出库单行号 → 本次开票数量（M3-T10 发票过账组装） */
    public record InvoicedLine(int lineNo, java.math.BigDecimal quantity) {
    }
}
