package com.sjherp.app.invocation;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.common.PageResult;
import com.sjherp.infra.persistence.invocation.AgentInvocation;
import com.sjherp.infra.persistence.invocation.AgentInvocationRepository.TokenSummary;

/**
 * Agent 调用观测查询 API 的响应 DTO（M1-T06）。
 */
public final class AgentInvocationDtos {

    /** 仅用于把落库的 detail JSON 字符串还原为 JSON 对象嵌入响应 */
    private static final ObjectMapper PLAIN_MAPPER = new ObjectMapper();

    private AgentInvocationDtos() {
    }

    /** 单条调用记录 */
    public record InvocationResponse(long id, String sessionId, String type, String model,
                                     String toolName, long durationMs, Integer promptTokens,
                                     Integer completionTokens, boolean success, JsonNode detail,
                                     String createdAt) {

        static InvocationResponse from(AgentInvocation invocation) {
            return new InvocationResponse(
                    invocation.id(),
                    invocation.sessionId(),
                    invocation.type().name(),
                    invocation.model(),
                    invocation.toolName(),
                    invocation.durationMs(),
                    invocation.promptTokens(),
                    invocation.completionTokens(),
                    invocation.success(),
                    parseDetail(invocation.detailJson()),
                    invocation.createdAt().toString());
        }

        /** detail 列为 JSON 字符串，响应中还原为对象；解析失败（不应发生）降级为原文 */
        private static JsonNode parseDetail(String detailJson) {
            if (detailJson == null) {
                return null;
            }
            try {
                return PLAIN_MAPPER.readTree(detailJson);
            } catch (JsonProcessingException e) {
                return PLAIN_MAPPER.getNodeFactory().textNode(detailJson);
            }
        }
    }

    /**
     * 分页响应（时间倒序）+ 本会话累计 token 汇总（X-6 成本看板数据源；
     * 汇总针对整个会话，不随分页变化）。
     */
    public record InvocationPageResponse(List<InvocationResponse> items, long total, int page, int size,
                                         long totalPromptTokens, long totalCompletionTokens) {

        static InvocationPageResponse of(PageResult<AgentInvocation> result, TokenSummary summary) {
            return new InvocationPageResponse(
                    result.items().stream().map(InvocationResponse::from).toList(),
                    result.total(), result.page(), result.size(),
                    summary.totalPromptTokens(), summary.totalCompletionTokens());
        }
    }
}
