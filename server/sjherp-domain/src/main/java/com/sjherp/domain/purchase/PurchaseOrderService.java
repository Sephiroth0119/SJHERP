package com.sjherp.domain.purchase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.event.DomainEventPublisher;

/**
 * 采购订单领域服务（M3-T05，路线图 §5 采购线）。
 *
 * <p>所有采购订单写操作的唯一入口（CLAUDE.md 原则 1）。纯 Java 零依赖：依赖采购订单仓储端口
 * {@link PurchaseOrderRepository}，由 app 层装配并加事务边界（@Transactional）。
 *
 * <p><b>下单不动库存</b>：采购订单只是采购承诺，本服务从不触碰库存——库存只在采购入库单
 * （M3-T06）过账时经库存唯一写入口产生。本服务另提供 {@link #applyReceipt} 供采购入库单服务在
 * 同一外层事务内回写各行累计到货量（部分收货跟踪），保证「采购订单是采购订单写操作的唯一入口」。
 */
public class PurchaseOrderService {

    private final PurchaseOrderRepository repository;

    /** 领域事件发布器：状态流转经它自动落 document.status_changed 审计（app 装配 SyncDomainEventPublisher） */
    private final DomainEventPublisher eventPublisher;

    public PurchaseOrderService(PurchaseOrderRepository repository, DomainEventPublisher eventPublisher) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher 不能为空");
    }

    /**
     * 创建采购订单（草稿）：行集合由 app 入口层组装后传入（商品 + 订购数量 + 采购单价）。
     *
     * @param docNo      单据号（PO-年月-序号，app 层用 DocumentNumberGenerator 生成）
     * @param supplierId 供应商 id（存在性/启用校验在 app 入口层）
     * @param orderDate  下单日期
     * @param remark     采购说明（可空）
     * @param lines      行输入（商品 + 订购数量 + 采购单价）
     * @param operator   操作人
     */
    @Audited(action = "purchase_order.create", targetType = "purchase_order")
    public PurchaseOrder create(String docNo, long supplierId, LocalDate orderDate, String remark,
                                List<PurchaseOrderLineInput> lines, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(docNo, "单据号不能为空");
        Objects.requireNonNull(lines, "采购订单行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("采购订单至少要有一行");
        }
        List<PurchaseOrderLine> domainLines = new ArrayList<>(lines.size());
        int lineNo = 1;
        for (PurchaseOrderLineInput input : lines) {
            domainLines.add(PurchaseOrderLine.create(lineNo++, input.productId(),
                    input.quantity(), input.unitPrice()));
        }
        PurchaseOrder order = PurchaseOrder.create(docNo, supplierId, orderDate, remark,
                domainLines, operator);
        order.registerEventPublisher(eventPublisher);
        repository.save(order);
        return order;
    }

    /** 审核采购订单：DRAFT → APPROVED（自此可被采购入库单引用收货）。 */
    @Audited(action = "purchase_order.approve", targetType = "purchase_order")
    public PurchaseOrder approve(String docNo, String operator) {
        requireOperator(operator);
        PurchaseOrder order = get(docNo);
        order.registerEventPublisher(eventPublisher);
        order.approve(operator);
        repository.save(order);
        return order;
    }

    /** 关闭采购订单：APPROVED → EXECUTING → COMPLETED，自此不再收货。 */
    @Audited(action = "purchase_order.close", targetType = "purchase_order")
    public PurchaseOrder close(String docNo, String operator) {
        requireOperator(operator);
        PurchaseOrder order = get(docNo);
        order.registerEventPublisher(eventPublisher);
        order.close(operator);
        repository.save(order);
        return order;
    }

    /**
     * 回写各行累计到货量（M3-T06 采购入库单过账时在同一外层事务内调用）。
     *
     * <p>收货过账的一环：采购入库单服务先经库存唯一写入口产生 PURCHASE_IN 流水，再调本方法把到货量
     * 累加回采购订单行（部分收货跟踪），二者同事务原子提交。累计超量由 {@link PurchaseOrder#receiveLine}
     * 拒绝（整批回滚，宁可拒绝不可破坏模型）。
     *
     * <p>是采购订单的写操作，照例 @Audited（与收货单自身的 purchase_receipt.post 审计并存，
     * 二者共同还原「收了哪张单、回写到哪张采购订单」的完整链路）。
     *
     * @param docNo    被引用的采购订单号
     * @param received 各行到货量（行号 → 本次到货数量，由收货单按引用关系组装）
     * @param operator 操作人（收货人）
     */
    @Audited(action = "purchase_order.apply_receipt", targetType = "purchase_order")
    public PurchaseOrder applyReceipt(String docNo, List<ReceivedLine> received, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(received, "到货行不能为空");
        PurchaseOrder order = get(docNo);
        for (ReceivedLine line : received) {
            order.receiveLine(line.lineNo(), line.quantity());
        }
        repository.save(order);
        return order;
    }

    /** 按单据号查（不存在抛 {@link PurchaseOrderNotFoundException} → API 404） */
    public PurchaseOrder get(String docNo) {
        return repository.findByDocNo(docNo)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(docNo));
    }

    /** 分页查询 */
    public PageResult<PurchaseOrder> search(PurchaseOrderQuery query) {
        return repository.search(Objects.requireNonNull(query, "query 不能为空"));
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }

    /** 收货回写的单行：采购订单行号 → 本次到货数量（M3-T06 收货过账组装） */
    public record ReceivedLine(int lineNo, BigDecimal quantity) {
    }
}
