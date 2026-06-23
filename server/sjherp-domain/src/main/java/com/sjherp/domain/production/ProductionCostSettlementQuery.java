package com.sjherp.domain.production;

import com.sjherp.domain.common.DocumentStatus;

/**
 * 月末成本结转单分页查询条件（M5-T06）。
 *
 * @param period 账期键 yyyyMM 过滤（null 表示不限）
 * @param status 单据状态过滤（null 表示不限）
 * @param page   页码（从 1 起）
 * @param size   每页大小
 */
public record ProductionCostSettlementQuery(String period, DocumentStatus status, int page, int size) {
}
