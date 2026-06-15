package com.sjherp.domain.production;

/**
 * 工单来源类型（M5-T03）：手工建单 or MRP 生产建议转单。
 */
public enum WorkOrderSourceType {
    /** 手工建单（计划员直接创建） */
    MANUAL,
    /** 从 MRP PRODUCTION 建议转单 */
    MRP_SUGGESTION
}
