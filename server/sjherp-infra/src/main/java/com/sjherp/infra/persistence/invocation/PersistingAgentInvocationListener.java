package com.sjherp.infra.persistence.invocation;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sjherp.agent.loop.AgentInvocationListener;
import com.sjherp.agent.tool.ToolRiskLevel;

/**
 * {@link AgentInvocationListener} 的落库实现（M1-T06）：每次 LLM 调用 / 工具调用
 * 写一行 agent_invocation 表（审计原则对 Agent 的延伸，X-6 成本看板数据源）。
 *
 * <p>当前为<b>同步写入</b>（在执行循环线程上）：单行 INSERT 相对 LLM 调用耗时可忽略。
 * TODO（性能优化）：高并发场景改为 @Async / 队列异步批量写入，观测落库移出对话关键路径。
 *
 * <p>兜底：落库失败只记 ERROR 日志、绝不抛出——观测失败不能影响对话
 * （AgentLoop 侧也有 try-catch 双保险）。
 */
public class PersistingAgentInvocationListener implements AgentInvocationListener {

    private static final Logger log = LoggerFactory.getLogger(PersistingAgentInvocationListener.class);

    /** detail 中错误信息的最大长度（防止超长堆栈/响应体膨胀） */
    private static final int ERROR_MAX_LENGTH = 500;

    private final AgentInvocationRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

    public PersistingAgentInvocationListener(AgentInvocationRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onLlmCall(String sessionId, int round, String model, long durationMs,
                          Integer promptTokens, Integer completionTokens,
                          boolean hasToolCalls, String error) {
        ObjectNode detail = mapper.createObjectNode();
        detail.put("round", round);
        detail.put("hasToolCalls", hasToolCalls);
        if (error != null) {
            detail.put("error", truncate(error));
        }
        insertQuietly(new AgentInvocation(null, sessionId, AgentInvocationType.LLM,
                model, null, durationMs, promptTokens, completionTokens,
                error == null, detail.toString(), Instant.now()));
    }

    /**
     * AgentLoop 之外的辅助 LLM 调用（如历史摘要，M1-T07 接入观测）：
     * type 仍为 LLM，purpose 写进 detail 以便与主链路调用区分。
     */
    @Override
    public void onAuxiliaryLlmCall(String sessionId, String purpose, String model, long durationMs,
                                   Integer promptTokens, Integer completionTokens, String error) {
        ObjectNode detail = mapper.createObjectNode();
        detail.put("purpose", purpose);
        if (error != null) {
            detail.put("error", truncate(error));
        }
        insertQuietly(new AgentInvocation(null, sessionId, AgentInvocationType.LLM,
                model, null, durationMs, promptTokens, completionTokens,
                error == null, detail.toString(), Instant.now()));
    }

    @Override
    public void onToolCall(String sessionId, String toolName, String argumentsJson, boolean success,
                           String resultSummary, long durationMs, ToolRiskLevel riskLevel,
                           boolean confirmed) {
        ObjectNode detail = mapper.createObjectNode();
        // 参数与结果摘要按字符串原样存（不解析：非法参数 JSON 也要可审计）
        detail.put("arguments", argumentsJson);
        detail.put("resultSummary", resultSummary);
        detail.put("riskLevel", riskLevel == null ? null : riskLevel.name());
        detail.put("confirmed", confirmed);
        insertQuietly(new AgentInvocation(null, sessionId, AgentInvocationType.TOOL,
                null, toolName, durationMs, null, null,
                success, detail.toString(), Instant.now()));
    }

    /** 落库兜底：任何异常只记日志，绝不影响对话主流程 */
    private void insertQuietly(AgentInvocation invocation) {
        try {
            repository.insert(invocation);
        } catch (RuntimeException e) {
            log.error("agent_invocation 落库失败（sessionId={}, type={}），观测记录丢失但不影响对话",
                    invocation.sessionId(), invocation.type(), e);
        }
    }

    private static String truncate(String text) {
        return text.length() <= ERROR_MAX_LENGTH ? text : text.substring(0, ERROR_MAX_LENGTH) + "...(已截断)";
    }
}
