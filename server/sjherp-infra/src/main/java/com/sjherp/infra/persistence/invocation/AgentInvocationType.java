package com.sjherp.infra.persistence.invocation;

/**
 * Agent 调用观测记录的类型（agent_invocation.type，MySQL ENUM('LLM','TOOL')）。
 */
public enum AgentInvocationType {

    /** 一次 LLM 调用（含终轮单独 JSON 调用与强制收尾调用） */
    LLM,

    /** 一次工具调用（含未知工具 / 校验失败 / 执行异常 / 用户取消等失败情形） */
    TOOL
}
