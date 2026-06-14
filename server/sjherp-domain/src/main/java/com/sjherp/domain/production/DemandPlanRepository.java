package com.sjherp.domain.production;

import java.util.List;
import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 需求计划仓储接口（M5-T02，领域层端口）。
 */
public interface DemandPlanRepository {

    /** 保存（新增或更新）。 */
    void save(DemandPlan plan);

    /** 按文档号查询。 */
    Optional<DemandPlan> findByDocNo(String docNo);

    /** 分页搜索。 */
    PageResult<DemandPlan> search(DemandPlanQuery query);

    /** 查询所有启用的需求计划（MRP 聚合预测需求用）。 */
    List<DemandPlan> findAllEnabled();
}
