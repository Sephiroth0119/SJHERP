package com.sjherp.agent.history;

import java.util.List;
import java.util.Objects;

import com.sjherp.agent.llm.LlmClient;
import com.sjherp.agent.llm.LlmMessage;
import com.sjherp.agent.llm.LlmRequestOptions;
import com.sjherp.agent.llm.LlmResponse;
import com.sjherp.agent.loop.AgentInvocationListener;
import com.sjherp.agent.session.MessageRole;

/**
 * 基于 {@link LlmClient} 的历史摘要实现（M1-T05）：单独调一次 LLM，
 * 把旧对话压缩为要点清单。低温度（0.2）保证摘要稳定、少发挥。
 *
 * <p>M1-T07 起注入的 LlmClient 由装配方按 summarizer 角色解析
 * （sjherp.llm.roles.summarizer），可与对话主链路用不同 provider/模型。
 *
 * <p>观测（M1-T07 还 M1-T05 遗留）：摘要调用在 {@link com.sjherp.agent.loop.AgentLoop}
 * 之外，本类手动回调 {@link AgentInvocationListener#onAuxiliaryLlmCall}
 * （purpose=summarize）落 agent_invocation；sessionId 经 {@link #forSession} 绑定。
 * 观测回调异常一律吞掉，绝不影响摘要主流程。
 *
 * <p>失败（网络 / 超时 / 空回复）抛 RuntimeException，
 * 由 {@link HistoryTrimmer} 按硬截断兜底，绝不阻塞主对话。
 */
public final class LlmHistorySummarizer implements HistorySummarizer {

    /** 观测 detail 中的调用目的标识 */
    static final String PURPOSE_SUMMARIZE = "summarize";

    /** 摘要提示词：压缩为要点清单，业务关键信息（尤其金额数字）必须原样保留 */
    private static final String SYSTEM_PROMPT = """
            你是对话历史压缩器。把给出的 ERP 业务对话历史压缩为一份要点清单（中文，markdown 无序列表）。
            硬性要求：
            1. 保留全部业务关键信息：单据号/编号（如 GAP-202606-0001）、客户名、供应商名、\
            商品名、仓库名、联系人、数量、金额、单价、未完成事项与待办；
            2. 金额、数量、单价等数字必须原样保留，绝不允许改写、四舍五入、换算或省略；
            3. 若提供了「已有摘要」，必须把它的全部要点合并进新摘要，不得丢失旧要点；
            4. 助手消息可能是 JSON 格式（结构化回复协议），提取其中的业务信息即可；
            5. 只输出要点清单本身，不要任何前后缀说明，不要用代码块包裹。
            """;

    /** 摘要采样温度：求稳不求发挥 */
    private static final double SUMMARY_TEMPERATURE = 0.2;

    private final LlmClient llmClient;
    /** 调用观测回调（可为 null = 不观测；保持框架零依赖，落库实现在 infra） */
    private final AgentInvocationListener invocationListener;

    public LlmHistorySummarizer(LlmClient llmClient) {
        this(llmClient, null);
    }

    /**
     * @param llmClient          摘要用 LLM 客户端（装配方按 summarizer 角色解析）
     * @param invocationListener 调用观测回调（null = 不观测）
     */
    public LlmHistorySummarizer(LlmClient llmClient, AgentInvocationListener invocationListener) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient 不能为空");
        this.invocationListener = invocationListener;
    }

    @Override
    public String summarize(String previousSummary, List<HistoryMessage> messages) {
        return summarize(null, previousSummary, messages);
    }

    /** 绑定会话 id 的视图：摘要观测记录携带 sessionId（LlmAgent 在裁剪前调用） */
    @Override
    public HistorySummarizer forSession(String sessionId) {
        return (previousSummary, messages) -> summarize(sessionId, previousSummary, messages);
    }

    private String summarize(String sessionId, String previousSummary, List<HistoryMessage> messages) {
        StringBuilder input = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            input.append("【已有摘要（必须全部合并进新摘要）】\n").append(previousSummary).append("\n\n");
        }
        input.append("【待压缩的对话历史】\n");
        for (HistoryMessage message : messages) {
            input.append(message.role() == MessageRole.USER ? "用户：" : "助手：")
                    .append(message.content() == null ? "" : message.content())
                    .append('\n');
        }

        long start = System.nanoTime();
        LlmResponse response;
        try {
            response = llmClient.chat(
                    List.of(LlmMessage.system(SYSTEM_PROMPT), LlmMessage.user(input.toString())),
                    LlmRequestOptions.builder().temperature(SUMMARY_TEMPERATURE).build());
        } catch (RuntimeException e) {
            notifyQuietly(sessionId, null, elapsedMillis(start), null, null,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw e;
        }
        long durationMs = elapsedMillis(start);
        Integer promptTokens = response.usage() == null ? null : response.usage().promptTokens();
        Integer completionTokens = response.usage() == null ? null : response.usage().completionTokens();

        String content = response.content();
        if (content == null || content.isBlank()) {
            notifyQuietly(sessionId, response.model(), durationMs, promptTokens, completionTokens,
                    "摘要模型返回空内容");
            throw new IllegalStateException("摘要模型返回空内容");
        }
        notifyQuietly(sessionId, response.model(), durationMs, promptTokens, completionTokens, null);
        return content.strip();
    }

    /** 观测回调兜底：回调抛出的任何异常都吞掉，绝不影响摘要主流程（口径与 AgentLoop 一致） */
    private void notifyQuietly(String sessionId, String model, long durationMs,
                               Integer promptTokens, Integer completionTokens, String error) {
        if (invocationListener == null) {
            return;
        }
        try {
            invocationListener.onAuxiliaryLlmCall(sessionId, PURPOSE_SUMMARIZE, model,
                    durationMs, promptTokens, completionTokens, error);
        } catch (RuntimeException e) {
            // 框架零依赖无日志门面：静默吞掉（落库实现自带 ERROR 日志兜底）
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
