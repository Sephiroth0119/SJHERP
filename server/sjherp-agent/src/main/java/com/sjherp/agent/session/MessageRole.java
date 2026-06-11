package com.sjherp.agent.session;

/**
 * 消息角色。
 */
public enum MessageRole {
    /** 系统提示 */
    SYSTEM,
    /** 用户输入（含点击选项产生的结构化输入） */
    USER,
    /** Agent 回复 */
    ASSISTANT,
    /** 工具执行结果 */
    TOOL
}
