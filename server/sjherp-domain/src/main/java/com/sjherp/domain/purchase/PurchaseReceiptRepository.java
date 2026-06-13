package com.sjherp.domain.purchase;

import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 采购入库单仓储接口（端口，实现在 infra 层，M3-T06）。
 *
 * <p>单据头与行项目同表族（purchase_receipt / purchase_receipt_line），保存时整聚合落库
 * （新建插头+插行、更新落状态）。读取按单据号装配整聚合。
 */
public interface PurchaseReceiptRepository {

    /** 保存采购入库单聚合（新建时回填头与各行自增 id；已存在时更新状态与冲销关联） */
    void save(PurchaseReceipt receipt);

    /** 按单据号查（不存在返回空） */
    Optional<PurchaseReceipt> findByDocNo(String docNo);

    /** 分页查询（按仓库/采购订单号/状态过滤，可空；按 id 倒序即最近创建在前） */
    PageResult<PurchaseReceipt> search(PurchaseReceiptQuery query);
}
