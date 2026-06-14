package com.sjherp.domain.production;

import com.sjherp.domain.common.ArchiveStatus;

/**
 * BOM 分页查询参数（只读，不可变）。
 *
 * @param productId 按父件商品 id 过滤（可空，空则不过滤）
 * @param status    按状态过滤（可空）
 * @param page      页码（1-based）
 * @param size      每页大小
 */
public record BillOfMaterialsQuery(
        Long productId,
        ArchiveStatus status,
        int page,
        int size) {
}
