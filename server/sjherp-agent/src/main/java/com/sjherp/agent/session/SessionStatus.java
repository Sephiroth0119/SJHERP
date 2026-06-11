package com.sjherp.agent.session;

/**
 * 会话状态。
 */
public enum SessionStatus {
    /** 进行中：可继续追加消息 */
    ACTIVE,
    /** 等待用户决策：Agent 已返回选项/表单，等待用户点击（Human-in-the-loop） */
    WAITING_USER,
    /** 已关闭：仅供查询，不再接受新消息 */
    CLOSED
}
