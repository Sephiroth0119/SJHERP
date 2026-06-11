package com.sjherp.agent.tool;

/**
 * 工具风险级别（M1-T03 Tool 安全壳）。
 *
 * <p>与协议层 {@code Option.RiskLevel}（选项卡片的渲染样式标记）刻意分开：
 * 本枚举声明的是<b>工具本身</b>的风险属性，由 Agent 执行循环在框架层强制拦截
 * （HIGH 且未带确认标记 → 不执行，返回待确认结果），不靠提示词自觉。
 */
public enum ToolRiskLevel {
    /** 普通操作：循环内直接执行 */
    NORMAL,
    /** 高风险操作（资金、过账、期间关账等）：必须由人显式确认后才执行 */
    HIGH
}
