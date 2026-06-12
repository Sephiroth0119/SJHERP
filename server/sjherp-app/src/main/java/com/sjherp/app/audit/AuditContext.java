package com.sjherp.app.audit;

/**
 * 审计上下文（M2-T07）：线程级传递 Agent 会话 id。
 *
 * <p>Agent 工具调用在 ChatService.handleMessage 的请求线程内同步执行
 * （ADR-001：执行循环同步占线程），由 ChatService 在处理消息前设置会话 id、
 * finally 中清除；审计切面据此把 Agent 写操作关联到来源会话（audit_log.session_id）。
 * 人工 REST 路径不设置，session_id 落空。
 */
public final class AuditContext {

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();

    private AuditContext() {
    }

    /** 进入 Agent 会话处理时设置（ChatService 调用） */
    public static void setSessionId(String sessionId) {
        SESSION_ID.set(sessionId);
    }

    /** 当前线程关联的会话 id（人工 REST 路径为 null） */
    public static String sessionId() {
        return SESSION_ID.get();
    }

    /** 处理结束后清除（必须在 finally 中调用，防止线程池串号） */
    public static void clear() {
        SESSION_ID.remove();
    }
}
