package com.sjherp.domain.sales;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.event.DomainEventPublisher;

/**
 * 销售订单领域服务（M3-T08，路线图 §5 销售线）。
 *
 * <p>所有销售订单写操作的唯一入口（CLAUDE.md 原则 1）。纯 Java 零依赖：只依赖销售订单仓储端口
 * {@link SalesOrderRepository}，由 app 层装配并加事务边界。
 *
 * <h2>库存语义</h2>
 * <b>下单不动库存</b>——销售订单仅是销售约定，不产生任何库存流水。可用库存检查
 * （下单时查可用量不足仅警告不阻断）在 app 入口层完成；真正扣减库存发生在销售出库单过账
 * （M3-T09，SALES_OUT 经库存唯一写入口）。客户/商品启用校验也在 app 入口层（与盘点/调拨同口径）。
 *
 * <p>累计发货量由销售出库单过账时经 {@link #recordDelivery} 回写到对应订单行
 * （单据出库服务在同一外层事务内调用，保证与库存过账原子）。
 */
public class SalesOrderService {

    private final SalesOrderRepository repository;

    /** 领域事件发布器：状态流转经它自动落 document.status_changed 审计（app 装配 SyncDomainEventPublisher） */
    private final DomainEventPublisher eventPublisher;

    public SalesOrderService(SalesOrderRepository repository, DomainEventPublisher eventPublisher) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher 不能为空");
    }

    /**
     * 创建销售订单（草稿）：行集合由 app 入口层组装后传入（商品 + 数量 + 销售单价）。
     *
     * @param docNo      单据号（SO-年月-序号，app 层用 DocumentNumberGenerator 生成）
     * @param customerId 客户 id（存在性/启用校验在 app 入口层）
     * @param orderDate  订单日期
     * @param remark     订单说明（可空）
     * @param lines      行输入（商品 + 数量 + 单价）
     * @param operator   操作人
     */
    @Audited(action = "sales_order.create", targetType = "sales_order")
    public SalesOrder create(String docNo, long customerId, LocalDate orderDate, String remark,
                             List<SalesOrderLineInput> lines, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(docNo, "单据号不能为空");
        Objects.requireNonNull(lines, "订单行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("销售订单至少要有一行");
        }
        List<SalesOrderLine> domainLines = new ArrayList<>(lines.size());
        int lineNo = 1;
        for (SalesOrderLineInput input : lines) {
            domainLines.add(SalesOrderLine.create(lineNo++, input.productId(),
                    input.quantity(), input.unitPrice()));
        }
        SalesOrder order = SalesOrder.create(docNo, customerId, orderDate, remark, domainLines, operator);
        order.registerEventPublisher(eventPublisher);
        repository.save(order);
        return order;
    }

    /** 审核销售订单：DRAFT → APPROVED（业务内容自此锁定，可据其发货）。 */
    @Audited(action = "sales_order.approve", targetType = "sales_order")
    public SalesOrder approve(String docNo, String operator) {
        requireOperator(operator);
        SalesOrder order = get(docNo);
        order.registerEventPublisher(eventPublisher);
        order.approve(operator);
        repository.save(order);
        return order;
    }

    /** 作废销售订单：仅 DRAFT 可作废（未产生任何业务影响）。 */
    @Audited(action = "sales_order.cancel", targetType = "sales_order")
    public SalesOrder cancel(String docNo, String operator) {
        requireOperator(operator);
        SalesOrder order = get(docNo);
        order.registerEventPublisher(eventPublisher);
        order.cancel(operator);
        repository.save(order);
        return order;
    }

    /**
     * 回写某行累计发货量（销售出库单 M3-T09 过账时调用）：
     * 校验本次发货不超过该行剩余可发量（{@link SalesOrderLine#addDelivered}），超发拒绝。
     *
     * <p>由出库单服务在同一外层事务内调用，与库存 SALES_OUT 过账原子提交。
     * 不单独标 @Audited（不是独立用户动作，随出库单 post 审计），故无 operator 参数。
     *
     * @param docNo  销售订单号
     * @param lineNo 订单行号
     * @param qty    本次发货量（基本单位，> 0）
     */
    public void recordDelivery(String docNo, int lineNo, java.math.BigDecimal qty) {
        SalesOrder order = get(docNo);
        order.lineByNo(lineNo).addDelivered(qty);
        repository.save(order);
    }

    /** 按单据号查（不存在抛 {@link SalesOrderNotFoundException} → API 404） */
    public SalesOrder get(String docNo) {
        return repository.findByDocNo(docNo)
                .orElseThrow(() -> new SalesOrderNotFoundException(docNo));
    }

    /** 分页查询 */
    public PageResult<SalesOrder> search(SalesOrderQuery query) {
        return repository.search(Objects.requireNonNull(query, "query 不能为空"));
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }
}
