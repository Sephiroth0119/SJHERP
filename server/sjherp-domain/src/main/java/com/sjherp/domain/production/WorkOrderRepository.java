package com.sjherp.domain.production;

import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 工单仓储接口（M5-T03）。实现由 infra 层提供，领域层仅定义契约。
 */
public interface WorkOrderRepository {

    /** 保存工单（新建时插入，已存在时更新状态与审计字段） */
    void save(WorkOrder workOrder);

    /** 按单号查询（不存在返回 empty） */
    Optional<WorkOrder> findByDocNo(String docNo);

    /** 分页查询（支持商品/状态过滤） */
    PageResult<WorkOrder> search(WorkOrderQuery query);
}
