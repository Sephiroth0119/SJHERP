package com.sjherp.domain.production;

import com.sjherp.domain.common.ArchiveStatus;

/**
 * 需求计划搜索条件（M5-T02）。
 *
 * @param status 档案状态（可空，不过滤）
 * @param page   页码（从 1 开始）
 * @param size   每页条数
 */
public record DemandPlanQuery(ArchiveStatus status, int page, int size) {
}
