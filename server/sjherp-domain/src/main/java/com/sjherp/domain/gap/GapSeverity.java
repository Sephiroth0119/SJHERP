package com.sjherp.domain.gap;

/**
 * 流程缺口严重度（Agent 结合用户描述判断，开发侧 triage 时可参考调整优先级）。
 */
public enum GapSeverity {

    /** 低：锦上添花，缺了也有变通办法 */
    LOW,

    /** 中：影响日常效率，但业务仍能跑 */
    MEDIUM,

    /** 高：业务流程被卡住，无替代方案 */
    HIGH
}
