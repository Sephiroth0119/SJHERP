package com.sjherp.agent.tool;

/**
 * 工具执行上下文。
 *
 * <p>审计要求（CLAUDE.md 不可妥协原则 3）：每次工具执行都必须能回答
 * "谁、在哪个会话里、依据什么指令"。本上下文随每次调用传入，由领域服务
 * 写入审计日志。
 *
 * @param sessionId   触发本次调用的会话 ID
 * @param userId      会话所属用户（最终责任人）
 * @param instruction 触发本次调用的用户原始指令（审计留痕）
 */
public record ToolContext(String sessionId, String userId, String instruction) {
}
