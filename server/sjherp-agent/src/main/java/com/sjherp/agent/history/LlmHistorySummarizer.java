package com.sjherp.agent.history;

import java.util.List;
import java.util.Objects;

import com.sjherp.agent.llm.LlmClient;
import com.sjherp.agent.llm.LlmMessage;
import com.sjherp.agent.llm.LlmRequestOptions;
import com.sjherp.agent.llm.LlmResponse;
import com.sjherp.agent.session.MessageRole;

/**
 * 基于 {@link LlmClient} 的历史摘要实现（M1-T05）：单独调一次 LLM，
 * 把旧对话压缩为要点清单。低温度（0.2）保证摘要稳定、少发挥。
 *
 * <p>失败（网络 / 超时 / 空回复）抛 RuntimeException，
 * 由 {@link HistoryTrimmer} 按硬截断兜底，绝不阻塞主对话。
 */
public final class LlmHistorySummarizer implements HistorySummarizer {

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

    public LlmHistorySummarizer(LlmClient llmClient) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient 不能为空");
    }

    @Override
    public String summarize(String previousSummary, List<HistoryMessage> messages) {
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
        LlmResponse response = llmClient.chat(
                List.of(LlmMessage.system(SYSTEM_PROMPT), LlmMessage.user(input.toString())),
                LlmRequestOptions.builder().temperature(SUMMARY_TEMPERATURE).build());
        String content = response.content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("摘要模型返回空内容");
        }
        return content.strip();
    }
}
