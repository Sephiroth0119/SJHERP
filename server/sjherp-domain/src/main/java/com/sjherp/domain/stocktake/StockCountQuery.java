package com.sjherp.domain.stocktake;

import com.sjherp.domain.common.DocumentStatus;

/**
 * 盘点单分页查询条件（M3-T03）。
 *
 * @param warehouseId 仓库 id 过滤（可空）
 * @param status      单据状态过滤（可空）
 * @param page        页码（从 1 起）
 * @param size        每页条数
 */
public record StockCountQuery(Long warehouseId, DocumentStatus status, int page, int size) {

    public StockCountQuery {
        if (page < 1) {
            throw new IllegalArgumentException("页码必须 >= 1: " + page);
        }
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("每页条数必须在 1-200 之间: " + size);
        }
    }
}
