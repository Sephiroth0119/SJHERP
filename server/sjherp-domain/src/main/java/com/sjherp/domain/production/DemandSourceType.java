package com.sjherp.domain.production;

/** 需求来源类型（M5-T02）。 */
public enum DemandSourceType {
    /** 手工预测（DemandPlan 录入） */
    FORECAST,
    /** 销售订单需求（实时聚合，不复制） */
    SALES_ORDER
}
