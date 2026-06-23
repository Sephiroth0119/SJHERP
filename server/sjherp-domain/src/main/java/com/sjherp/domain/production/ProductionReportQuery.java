package com.sjherp.domain.production;

import com.sjherp.domain.common.DocumentStatus;

/**
 * 报工单分页查询条件（M5-T05）。
 *
 * @param workOrderDocNo  关联工单号过滤（null 表示不限）
 * @param status          单据状态过滤（null 表示不限）
 * @param page            页码（从 1 起，与其他 Query 一致）
 * @param size            每页大小
 */
public record ProductionReportQuery(String workOrderDocNo, DocumentStatus status, int page, int size) {
}
