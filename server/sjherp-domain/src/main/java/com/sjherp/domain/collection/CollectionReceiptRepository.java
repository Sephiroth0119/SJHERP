package com.sjherp.domain.collection;

import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 收款单仓储接口（端口，实现在 infra 层，M4-T04b）。
 *
 * <p>单据头与行项目同表族（collection_receipt / collection_receipt_line），保存时整聚合落库
 * （新建插头+插行、更新落状态）。读取按单据号装配整聚合。
 */
public interface CollectionReceiptRepository {

    /** 保存收款单聚合（新建时回填头与各行自增 id；已存在时更新状态与冲销关联） */
    void save(CollectionReceipt receipt);

    /** 按单据号查（不存在返回空） */
    Optional<CollectionReceipt> findByDocNo(String docNo);

    /** 分页查询（按客户/资金账户/状态过滤，可空；按 id 倒序即最近创建在前） */
    PageResult<CollectionReceipt> search(CollectionReceiptQuery query);
}
