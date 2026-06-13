package com.sjherp.domain.sales;

import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 销售订单仓储接口（端口，实现在 infra 层，M3-T08）。
 *
 * <p>单据头与行项目同表族（sales_order / sales_order_line），保存时整聚合落库
 * （新建插头+插行、更新落状态 + 各行累计发货量）。读取按单据号装配整聚合。
 */
public interface SalesOrderRepository {

    /** 保存销售订单聚合（新建时回填头与各行自增 id；已存在时更新状态、累计发货量与冲销关联） */
    void save(SalesOrder order);

    /** 按单据号查（不存在返回空） */
    Optional<SalesOrder> findByDocNo(String docNo);

    /** 分页查询（按客户/状态过滤，可空；按 id 倒序即最近创建在前） */
    PageResult<SalesOrder> search(SalesOrderQuery query);
}
