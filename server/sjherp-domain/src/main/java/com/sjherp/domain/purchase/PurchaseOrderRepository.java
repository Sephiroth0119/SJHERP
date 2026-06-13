package com.sjherp.domain.purchase;

import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 采购订单仓储接口（端口，实现在 infra 层，M3-T05）。
 *
 * <p>单据头与行项目同表族（purchase_order / purchase_order_line），保存时整聚合落库
 * （新建插头+插行；更新落头状态 + 行已到货量）。读取按单据号装配整聚合。
 */
public interface PurchaseOrderRepository {

    /** 保存采购订单聚合（新建时回填头与各行自增 id；已存在时更新头状态与各行已到货量） */
    void save(PurchaseOrder order);

    /** 按单据号查（不存在返回空） */
    Optional<PurchaseOrder> findByDocNo(String docNo);

    /** 分页查询（按供应商/状态过滤，可空；按 id 倒序即最近创建在前） */
    PageResult<PurchaseOrder> search(PurchaseOrderQuery query);
}
