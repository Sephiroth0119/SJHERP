package com.sjherp.domain.stocktake;

import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 盘点单仓储接口（端口，实现在 infra 层，M3-T03）。
 *
 * <p>单据头与行项目同表族（stock_count / stock_count_line），保存时整聚合落库
 * （新建插头+插行、更新落状态与实盘数量）。读取按单据号或 id 装配整聚合。
 */
public interface StockCountRepository {

    /** 保存盘点单聚合（新建时回填头与各行自增 id；已存在时更新状态与各行实盘数量） */
    void save(StockCountDocument document);

    /** 按单据号查（不存在返回空） */
    Optional<StockCountDocument> findByDocNo(String docNo);

    /** 分页查询（按仓库/状态过滤，可空；按 id 倒序即最近创建在前） */
    PageResult<StockCountDocument> search(StockCountQuery query);
}
