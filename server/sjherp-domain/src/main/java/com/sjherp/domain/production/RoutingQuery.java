package com.sjherp.domain.production;

import com.sjherp.domain.common.ArchiveStatus;

/**
 * 工艺路线分页查询参数。
 *
 * @param productId 按产品 id 过滤（可空）
 * @param status    按状态过滤（可空）
 * @param page      页码（1-based）
 * @param size      每页大小
 */
public record RoutingQuery(
        Long productId,
        ArchiveStatus status,
        int page,
        int size) {
}
