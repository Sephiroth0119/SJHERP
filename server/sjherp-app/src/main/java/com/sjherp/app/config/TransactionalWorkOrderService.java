package com.sjherp.app.config;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.WorkOrder;
import com.sjherp.domain.production.WorkOrderQuery;
import com.sjherp.domain.production.WorkOrderService;

/**
 * 工单领域服务的事务包装（M5-T03）——调用方（REST 控制器）一律注入本类，
 * 不要直接注入 {@link WorkOrderService}。
 *
 * <p>写操作须在单一事务内原子完成（状态流转 + 仓储 save）；
 * 读操作以 readOnly = true 打开只读事务（InnoDB 优化路径）。
 * 事务包装约定同 {@link TransactionalMrpService}。
 */
public class TransactionalWorkOrderService {

    private final WorkOrderService delegate;

    public TransactionalWorkOrderService(WorkOrderService delegate) {
        this.delegate = delegate;
    }

    // ---------------------------------------------------------------- 写操作

    /** 手工建单（DRAFT） */
    @Transactional
    public WorkOrder createManual(
            long productId, BigDecimal plannedQty, long unitId,
            Integer bomVersion, Integer routingVersion, Long warehouseId,
            LocalDate plannedStartDate, LocalDate plannedEndDate,
            String remark, String operator) {
        return delegate.createManual(productId, plannedQty, unitId,
                bomVersion, routingVersion, warehouseId,
                plannedStartDate, plannedEndDate, remark, operator);
    }

    /** 从 MRP 生产建议建单（DRAFT） */
    @Transactional
    public WorkOrder createFromSuggestion(String mrpRunDocNo, long productId, String operator) {
        return delegate.createFromSuggestion(mrpRunDocNo, productId, operator);
    }

    /** 下达工单（DRAFT → APPROVED） */
    @Transactional
    public WorkOrder release(String docNo, String operator) {
        return delegate.release(docNo, operator);
    }

    /** 开工（APPROVED → EXECUTING） */
    @Transactional
    public WorkOrder start(String docNo, String operator) {
        return delegate.start(docNo, operator);
    }

    /** 完工（EXECUTING → COMPLETED） */
    @Transactional
    public WorkOrder complete(String docNo, String operator) {
        return delegate.complete(docNo, operator);
    }

    /** 作废（DRAFT → CANCELLED） */
    @Transactional
    public WorkOrder cancel(String docNo, String operator) {
        return delegate.cancel(docNo, operator);
    }

    /** 冲销（APPROVED → REVERSED） */
    @Transactional
    public WorkOrder reverse(String docNo, String operator) {
        return delegate.reverse(docNo, operator);
    }

    // ---------------------------------------------------------------- 只读查询

    /** 按单号查询（不存在抛 WorkOrderNotFoundException → 404） */
    @Transactional(readOnly = true)
    public WorkOrder get(String docNo) {
        return delegate.get(docNo);
    }

    /** 分页查询工单 */
    @Transactional(readOnly = true)
    public PageResult<WorkOrder> search(WorkOrderQuery query) {
        return delegate.search(query);
    }
}
