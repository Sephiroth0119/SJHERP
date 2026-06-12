package com.sjherp.infra.persistence.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.domain.common.PageResult;

/**
 * PersistingAgentInvocationListener 单元测试（M1-T06）：假仓储捕获插入——
 * LLM / TOOL 两类回调到 agent_invocation 行的字段映射、detail JSON 结构、
 * 错误信息截断、落库失败兜底（绝不抛出）。
 */
class PersistingAgentInvocationListenerTest {

    /** 捕获插入记录的假仓储（查询方法不在本测试范围） */
    static final class CapturingRepository implements AgentInvocationRepository {

        final List<AgentInvocation> inserted = new ArrayList<>();

        @Override
        public void insert(AgentInvocation invocation) {
            inserted.add(invocation);
        }

        @Override
        public PageResult<AgentInvocation> findBySession(String sessionId, int page, int size) {
            throw new UnsupportedOperationException("本测试不查询");
        }

        @Override
        public TokenSummary sumTokens(String sessionId) {
            throw new UnsupportedOperationException("本测试不汇总");
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final CapturingRepository repository = new CapturingRepository();
    private final PersistingAgentInvocationListener listener =
            new PersistingAgentInvocationListener(repository);

    private JsonNode detailOf(AgentInvocation invocation) throws JsonProcessingException {
        return mapper.readTree(invocation.detailJson());
    }

    @Test
    void llmCallMapsToLlmRowWithUsageAndDetail() throws Exception {
        listener.onLlmCall("session-1", 2, "deepseek-chat", 1234L, 321, 87, true, null);

        assertThat(repository.inserted).hasSize(1);
        AgentInvocation row = repository.inserted.get(0);
        assertThat(row.sessionId()).isEqualTo("session-1");
        assertThat(row.type()).isEqualTo(AgentInvocationType.LLM);
        assertThat(row.model()).isEqualTo("deepseek-chat");
        assertThat(row.toolName()).isNull();
        assertThat(row.durationMs()).isEqualTo(1234L);
        assertThat(row.promptTokens()).isEqualTo(321);
        assertThat(row.completionTokens()).isEqualTo(87);
        assertThat(row.success()).isTrue();
        assertThat(row.createdAt()).isNotNull();

        JsonNode detail = detailOf(row);
        assertThat(detail.get("round").asInt()).isEqualTo(2);
        assertThat(detail.get("hasToolCalls").asBoolean()).isTrue();
        assertThat(detail.has("error")).isFalse();
    }

    @Test
    void llmFailureMapsToUnsuccessfulRowWithTruncatedError() throws Exception {
        String longError = "E".repeat(600);
        listener.onLlmCall("session-1", 1, null, 50L, null, null, false, longError);

        AgentInvocation row = repository.inserted.get(0);
        assertThat(row.success()).isFalse();
        assertThat(row.model()).isNull();
        assertThat(row.promptTokens()).isNull();

        JsonNode detail = detailOf(row);
        // 错误信息按 500 字符截断，防止超长堆栈/响应体膨胀
        assertThat(detail.get("error").asText()).hasSize(500 + "...(已截断)".length())
                .endsWith("...(已截断)");
    }

    @Test
    void auxiliaryLlmCallMapsToLlmRowWithPurposeInDetail() throws Exception {
        // M1-T07：摘要等 AgentLoop 之外的辅助 LLM 调用——type 仍为 LLM，purpose 进 detail
        listener.onAuxiliaryLlmCall("session-1", "summarize", "deepseek-chat", 800L, 1500, 120, null);

        AgentInvocation row = repository.inserted.get(0);
        assertThat(row.sessionId()).isEqualTo("session-1");
        assertThat(row.type()).isEqualTo(AgentInvocationType.LLM);
        assertThat(row.model()).isEqualTo("deepseek-chat");
        assertThat(row.toolName()).isNull();
        assertThat(row.durationMs()).isEqualTo(800L);
        assertThat(row.promptTokens()).isEqualTo(1500);
        assertThat(row.completionTokens()).isEqualTo(120);
        assertThat(row.success()).isTrue();

        JsonNode detail = detailOf(row);
        assertThat(detail.get("purpose").asText()).isEqualTo("summarize");
        assertThat(detail.has("error")).isFalse();
        assertThat(detail.has("round")).isFalse();
    }

    @Test
    void auxiliaryLlmFailureMapsToUnsuccessfulRowWithTruncatedError() throws Exception {
        listener.onAuxiliaryLlmCall("session-1", "summarize", null, 60_000L, null, null, "E".repeat(600));

        AgentInvocation row = repository.inserted.get(0);
        assertThat(row.success()).isFalse();
        assertThat(row.model()).isNull();

        JsonNode detail = detailOf(row);
        assertThat(detail.get("purpose").asText()).isEqualTo("summarize");
        assertThat(detail.get("error").asText()).endsWith("...(已截断)");
    }

    @Test
    void toolCallMapsToToolRowWithDetail() throws Exception {
        listener.onToolCall("session-1", "echo", "{\"message\":\"hi\"}", true,
                "{\"success\":true,\"data\":{\"echo\":\"hi\"}}", 7L, ToolRiskLevel.NORMAL, false);

        AgentInvocation row = repository.inserted.get(0);
        assertThat(row.type()).isEqualTo(AgentInvocationType.TOOL);
        assertThat(row.toolName()).isEqualTo("echo");
        assertThat(row.model()).isNull();
        assertThat(row.durationMs()).isEqualTo(7L);
        // token 列只属于 LLM 记录
        assertThat(row.promptTokens()).isNull();
        assertThat(row.completionTokens()).isNull();
        assertThat(row.success()).isTrue();

        JsonNode detail = detailOf(row);
        // 参数按字符串原样存（不解析：非法参数 JSON 也要可审计）
        assertThat(detail.get("arguments").asText()).isEqualTo("{\"message\":\"hi\"}");
        assertThat(detail.get("resultSummary").asText()).contains("\"echo\":\"hi\"");
        assertThat(detail.get("riskLevel").asText()).isEqualTo("NORMAL");
        assertThat(detail.get("confirmed").asBoolean()).isFalse();
    }

    @Test
    void unknownToolRiskLevelIsStoredAsJsonNull() throws Exception {
        listener.onToolCall("session-1", "ghost", "{}", false,
                "{\"success\":false,\"error\":\"未知工具\"}", 0L, null, false);

        JsonNode detail = detailOf(repository.inserted.get(0));
        assertThat(detail.get("riskLevel").isNull()).isTrue();
        assertThat(repository.inserted.get(0).success()).isFalse();
    }

    @Test
    void confirmedHighRiskToolCallKeepsConfirmedFlag() throws Exception {
        listener.onToolCall("session-1", "demo_post_document", "{\"documentId\":\"DOC-1\"}", true,
                "{\"success\":true,\"data\":{\"status\":\"POSTED\"}}", 15L, ToolRiskLevel.HIGH, true);

        JsonNode detail = detailOf(repository.inserted.get(0));
        assertThat(detail.get("riskLevel").asText()).isEqualTo("HIGH");
        assertThat(detail.get("confirmed").asBoolean()).isTrue();
    }

    @Test
    void repositoryFailureIsSwallowed() {
        AgentInvocationRepository broken = new AgentInvocationRepository() {
            @Override
            public void insert(AgentInvocation invocation) {
                throw new IllegalStateException("数据库挂了");
            }

            @Override
            public PageResult<AgentInvocation> findBySession(String sessionId, int page, int size) {
                throw new UnsupportedOperationException();
            }

            @Override
            public TokenSummary sumTokens(String sessionId) {
                throw new UnsupportedOperationException();
            }
        };
        PersistingAgentInvocationListener failing = new PersistingAgentInvocationListener(broken);
        // 落库失败只记日志：观测失败绝不影响对话主流程
        assertThatCode(() -> failing.onLlmCall("s", 1, "m", 1L, 1, 1, false, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> failing.onToolCall("s", "t", "{}", true, "ok", 1L,
                ToolRiskLevel.NORMAL, false)).doesNotThrowAnyException();
        assertThatCode(() -> failing.onAuxiliaryLlmCall("s", "summarize", "m", 1L, 1, 1, null))
                .doesNotThrowAnyException();
    }
}
