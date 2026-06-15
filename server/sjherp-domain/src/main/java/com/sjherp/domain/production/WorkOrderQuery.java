package com.sjherp.domain.production;

import com.sjherp.domain.common.DocumentStatus;

/**
 * 工单分页查询条件（M5-T03）。
 *
 * @param productId 按商品 id 过滤（null 表示不限）
 * @param status    按状态过滤（null 表示不限）
 * @param page      页码（从 1 开始）
 * @param size      每页条数
 */
public record WorkOrderQuery(Long productId, DocumentStatus status, int page, int size) {
}
