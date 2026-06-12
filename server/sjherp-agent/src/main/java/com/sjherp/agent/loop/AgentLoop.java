package com.sjherp.agent.loop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.llm.LlmClient;
import com.sjherp.agent.llm.LlmMessage;
import com.sjherp.agent.llm.LlmRequestOptions;
import com.sjherp.agent.llm.LlmResponse;
import com.sjherp.agent.llm.ToolCall;
import com.sjherp.agent.llm.ToolChoice;
import com.sjherp.agent.llm.ToolDefinition;
import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolArgumentValidator;
import com.sjherp.agent.tool.ToolArgumentsCodec;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolDefinitions;
import com.sjherp.agent.tool.ToolPermissionChecker;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;

/**
 * Agent 执行循环（M1-T02）：调 LLM（带 tools）→ 模型返回 toolCalls 则逐个执行工具、
 * 结果以 TOOL 消息回灌 → 继续，直到模型产出最终文本。协议 JSON 由上层解析，
 * 循环只负责消息流编排。
 *
 * <p>防护（M1-T02/T03，框架级、不靠提示词自觉）：
 * <ul>
 *   <li>最大迭代次数（默认 8，可配）：用尽后强制做一次不带工具的终轮调用收尾；</li>
 *   <li>整体超时预算：超出抛 {@link AgentLoopTimeoutException}；</li>
 *   <li>单工具执行异常 / 未知工具名 / 参数校验失败 / 权限不足：错误以 TOOL 消息
 *       回灌让模型自行调整，不中断循环；</li>
 *   <li><b>高风险拦截</b>：riskLevel=HIGH 且未带确认标记的调用一律不执行，中断循环
 *       返回 {@link PendingToolCall}（Human-in-the-loop），确认后经 {@link #resume} 恢复。</li>
 * </ul>
 *
 * <p>零依赖约束：LLM 调用、参数 JSON 编解码、参数校验、权限校验全部经接口注入，
 * 本类不感知任何具体实现。
 *
 * <p>可观测性（M1-T06）：注入 {@link AgentInvocationListener} 后，每次 LLM 调用与
 * 工具调用处理完成都会回调（含终轮单独 JSON 调用与强制收尾调用）；listener 为 null
 * 时零开销，回调异常一律吞掉——观测失败绝不中断对话。
 */
public final class AgentLoop {

    private final LlmClient llmClient;
    private final ToolArgumentsCodec argumentsCodec;
    private final ToolArgumentValidator argumentValidator;
    private final ToolPermissionChecker permissionChecker;
    /** 调用观测回调（可为 null = 不观测、无开销） */
    private final AgentInvocationListener invocationListener;

    public AgentLoop(LlmClient llmClient, ToolArgumentsCodec argumentsCodec,
                     ToolArgumentValidator argumentValidator, ToolPermissionChecker permissionChecker) {
        this(llmClient, argumentsCodec, argumentValidator, permissionChecker, null);
    }

    public AgentLoop(LlmClient llmClient, ToolArgumentsCodec argumentsCodec,
                     ToolArgumentValidator argumentValidator, ToolPermissionChecker permissionChecker,
                     AgentInvocationListener invocationListener) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient 不能为空");
        this.argumentsCodec = Objects.requireNonNull(argumentsCodec, "argumentsCodec 不能为空");
        this.argumentValidator = Objects.requireNonNull(argumentValidator, "argumentValidator 不能为空");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker 不能为空");
        this.invocationListener = invocationListener;
    }

    /** 从头开始一次执行循环 */
    public AgentLoopResult run(AgentLoopRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        Long deadline = deadlineOf(request);
        List<LlmMessage> messages = baseMessages(request);
        return loop(request, messages, new ArrayList<>(), deadline);
    }

    /**
     * 从高风险拦截的中断现场恢复循环。
     *
     * <p>重建中断时刻的上下文（模型的工具调用消息 + 已执行结果），然后：
     * <ul>
     *   <li>{@code confirmed=true}：以"已确认"标记执行待确认调用与该轮剩余调用
     *       （剩余调用仍受高风险门禁约束，可能再次产生待确认结果）；</li>
     *   <li>{@code confirmed=false}：待确认调用及剩余调用一律不执行，以
     *       "用户已取消"错误回灌，让模型向用户致意并继续对话。</li>
     * </ul>
     *
     * @param request   恢复时的循环输入（history 应为会话当前历史，含用户点击确认/取消的消息）
     * @param pending   先前中断时返回并由上层持久化的现场
     * @param confirmed 用户是否点击了确认
     */
    public AgentLoopResult resume(AgentLoopRequest request, PendingToolCall pending, boolean confirmed) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(pending, "pending 不能为空");
        Long deadline = deadlineOf(request);
        List<LlmMessage> messages = baseMessages(request);
        List<ToolCallRecord> records = new ArrayList<>();

        // 重建中断现场：该轮 assistant 工具调用消息 + 已执行调用的结果
        messages.add(LlmMessage.assistant(pending.assistantContent(), pending.toolCalls()));
        List<LlmMessage> toolMessages = new ArrayList<>();
        for (PendingToolCall.ExecutedResult executed : pending.executedResults()) {
            toolMessages.add(LlmMessage.tool(executed.toolCallId(), executed.content()));
        }

        int pendingIndex = indexOfPendingCall(pending);
        if (confirmed) {
            // 带确认标记执行待确认调用，并继续执行该轮剩余调用（门禁仍生效）
            PendingToolCall next = executeCalls(request, pending.assistantContent(), pending.toolCalls(),
                    pendingIndex, pending.pendingToolCallId(), records, toolMessages);
            if (next != null) {
                return AgentLoopResult.pendingConfirmation(next, records);
            }
        } else {
            // 取消：待确认调用与剩余调用一律回灌"用户已取消"（每个 tool_call_id 都必须有应答）
            Map<String, Tool> toolsByName = toolsByName(request);
            for (int i = pendingIndex; i < pending.toolCalls().size(); i++) {
                ToolCall call = pending.toolCalls().get(i);
                String content = errorContent("用户已取消该高风险操作，工具未执行。请告知用户并继续对话。");
                records.add(new ToolCallRecord(call.id(), call.name(), call.argumentsJson(), content, false, 0));
                toolMessages.add(LlmMessage.tool(call.id(), content));
                // 观测：取消的调用也上报（success=false、耗时 0、未经确认），口径与 ToolCallRecord 一致
                notifyToolCall(request, call, toolsByName.get(call.name()), false, content, 0, false);
            }
        }
        messages.addAll(toolMessages);
        return loop(request, messages, records, deadline);
    }

    // ---------------------------------------------------------------- 循环主体

    /**
     * 循环主体：每轮调 LLM；返回 toolCalls 则执行并回灌，否则按终轮 JSON 模式收尾。
     * 迭代预算用尽后强制做一次不带工具的终轮调用。
     */
    private AgentLoopResult loop(AgentLoopRequest request, List<LlmMessage> messages,
                                 List<ToolCallRecord> records, Long deadline) {
        List<ToolDefinition> definitions = ToolDefinitions.fromAll(request.tools());
        boolean hasTools = !definitions.isEmpty();
        // LLM 调用序号（观测用，1 起）：含终轮单独 JSON 调用与强制收尾调用
        int llmRound = 0;

        for (int iteration = 1; iteration <= request.maxIterations(); iteration++) {
            checkDeadline(request, deadline);
            // 无工具时直接按终轮参数调用：行为退化为单轮对话
            LlmRequestOptions options = hasTools ? toolRoundOptions(request, definitions)
                    : finalRoundOptions(request);
            LlmResponse response = observedChat(request, messages, options, ++llmRound);

            if (!response.hasToolCalls()) {
                if (hasTools && request.finalJsonMode() == FinalJsonMode.JSON_SEPARATE_FINAL_CALL) {
                    // 厂商不支持 tools+json_object 同时携带：终轮单独再调一次（丢弃本次自由文本）
                    checkDeadline(request, deadline);
                    LlmResponse finalResponse = observedChat(request, messages, finalRoundOptions(request), ++llmRound);
                    return AgentLoopResult.completed(finalResponse.content(), records);
                }
                return AgentLoopResult.completed(response.content(), records);
            }

            // 工具轮：逐个执行（高风险未确认 → 中断返回待确认现场）
            List<LlmMessage> toolMessages = new ArrayList<>();
            PendingToolCall pending = executeCalls(request, response.content(), response.toolCalls(),
                    0, null, records, toolMessages);
            if (pending != null) {
                return AgentLoopResult.pendingConfirmation(pending, records);
            }
            messages.add(LlmMessage.assistant(response.content(), response.toolCalls()));
            messages.addAll(toolMessages);
        }

        // 迭代预算用尽：强制终轮（不带工具），保证循环必然收敛产出文本
        checkDeadline(request, deadline);
        LlmResponse forced = observedChat(request, messages, finalRoundOptions(request), ++llmRound);
        return AgentLoopResult.completed(forced.content(), records);
    }

    /**
     * 带观测的 LLM 调用（M1-T06）：成功与失败都回调 {@link AgentInvocationListener#onLlmCall}，
     * 失败时记录错误信息后原样抛出（由上层兜底）。listener 为 null 时等价于直接调用。
     */
    private LlmResponse observedChat(AgentLoopRequest request, List<LlmMessage> messages,
                                     LlmRequestOptions options, int round) {
        if (invocationListener == null) {
            return llmClient.chat(messages, options);
        }
        long start = System.nanoTime();
        LlmResponse response;
        try {
            response = llmClient.chat(messages, options);
        } catch (RuntimeException e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            notifyLlmCall(request, round, null, elapsedMillis(start), null, null, false, error);
            throw e;
        }
        notifyLlmCall(request, round, response.model(), elapsedMillis(start),
                response.usage() == null ? null : response.usage().promptTokens(),
                response.usage() == null ? null : response.usage().completionTokens(),
                response.hasToolCalls(), null);
        return response;
    }

    /** 观测回调兜底：回调异常一律吞掉，绝不影响主流程 */
    private void notifyLlmCall(AgentLoopRequest request, int round, String model, long durationMs,
                               Integer promptTokens, Integer completionTokens,
                               boolean hasToolCalls, String error) {
        try {
            invocationListener.onLlmCall(sessionIdOf(request), round, model, durationMs,
                    promptTokens, completionTokens, hasToolCalls, error);
        } catch (RuntimeException ignored) {
            // 观测失败不中断对话（实现方自行记录日志）
        }
    }

    /** 观测回调兜底：回调异常一律吞掉，绝不影响主流程 */
    private void notifyToolCall(AgentLoopRequest request, ToolCall call, Tool tool, boolean success,
                                String resultContent, long durationMs, boolean confirmed) {
        if (invocationListener == null) {
            return;
        }
        try {
            invocationListener.onToolCall(sessionIdOf(request), call.name(), call.argumentsJson(),
                    success, truncateSummary(resultContent), durationMs,
                    tool == null ? null : tool.riskLevel(), confirmed);
        } catch (RuntimeException ignored) {
            // 观测失败不中断对话（实现方自行记录日志）
        }
    }

    private static String sessionIdOf(AgentLoopRequest request) {
        return request.context() == null ? null : request.context().sessionId();
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * 执行一轮 assistant 工具调用中从 {@code startIndex} 起的调用。
     *
     * @param confirmedCallId 带"已确认"标记的调用 id（仅恢复执行时非 null）；
     *                        高风险调用 id 与之相等才放行
     * @param toolMessages    出参：每个调用的 TOOL 回灌消息（追加）
     * @return 遇到需人工确认的高风险调用时返回现场（此时该调用及其后调用均未执行）；
     *         全部执行完返回 null
     */
    private PendingToolCall executeCalls(AgentLoopRequest request, String assistantContent,
                                         List<ToolCall> calls, int startIndex, String confirmedCallId,
                                         List<ToolCallRecord> records, List<LlmMessage> toolMessages) {
        Map<String, Tool> toolsByName = toolsByName(request);
        for (int i = startIndex; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            Tool tool = toolsByName.get(call.name());

            // 高风险门禁（框架级拦截）：HIGH 且未带确认标记 → 不执行，中断循环
            if (tool != null && tool.riskLevel() == ToolRiskLevel.HIGH
                    && !Objects.equals(call.id(), confirmedCallId)) {
                List<PendingToolCall.ExecutedResult> executed = toolMessages.stream()
                        .map(m -> new PendingToolCall.ExecutedResult(m.toolCallId(), m.content()))
                        .toList();
                return new PendingToolCall(assistantContent, calls, executed, call.id(),
                        confirmationSummary(tool, call));
            }

            String content = executeSingle(request, tool, call,
                    Objects.equals(call.id(), confirmedCallId), records);
            toolMessages.add(LlmMessage.tool(call.id(), content));
        }
        return null;
    }

    /**
     * 执行单个工具调用并记录。所有失败情形（未知工具 / 权限不足 / 参数非法 /
     * 校验失败 / 执行异常）都转成错误 JSON 回灌，不抛出、不中断循环。
     *
     * @param confirmed 本次执行是否经过用户高风险确认（观测记录用）
     */
    private String executeSingle(AgentLoopRequest request, Tool tool, ToolCall call,
                                 boolean confirmed, List<ToolCallRecord> records) {
        ToolContext context = request.context();
        long start = System.nanoTime();
        boolean success = false;
        String content;
        if (tool == null) {
            content = errorContent("未知工具: " + call.name() + "。该工具未注册，请改用已提供的工具或直接回答。");
        } else if (tool.requiredPermission() != null && !permissionChecker.isAllowed(tool, context)) {
            // 权限点校验：本期为接口占位（默认放行），M2-T06 接真实权限
            content = errorContent("权限不足: 当前用户无权执行工具 " + call.name()
                    + "（需要权限点 " + tool.requiredPermission() + "）。请告知用户并改用其他方式。");
        } else {
            content = parseValidateAndExecute(tool, call, context);
            success = content.startsWith(SUCCESS_PREFIX);
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        records.add(new ToolCallRecord(call.id(), call.name(), call.argumentsJson(),
                content, success, elapsedMillis));
        notifyToolCall(request, call, tool, success, content, elapsedMillis, confirmed);
        return content;
    }

    /** 参数解析 → schema 校验 → 执行；任何 RuntimeException 都转错误回灌 */
    private String parseValidateAndExecute(Tool tool, ToolCall call, ToolContext context) {
        Map<String, Object> arguments;
        try {
            arguments = argumentsCodec.parse(call.argumentsJson());
        } catch (RuntimeException e) {
            return errorContent("调用参数不是合法 JSON: " + e.getMessage() + "。请修正参数后重试。");
        }
        List<String> validationErrors = argumentValidator.validate(tool.parameterSchema(), arguments);
        if (!validationErrors.isEmpty()) {
            return errorContent("参数校验失败: " + String.join("；", validationErrors) + "。请修正参数后重试。");
        }
        try {
            ToolResult result = tool.execute(arguments, context);
            return resultContent(result);
        } catch (RuntimeException e) {
            // 单工具异常不中断循环：错误回灌让模型自行调整（换参数 / 换工具 / 向用户说明）
            return errorContent("工具执行异常: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 辅助

    /** 成功结果回灌内容的固定前缀（serialize 保持插入顺序，success 在最前） */
    private static final String SUCCESS_PREFIX = "{\"success\":true";

    /** 观测回调中工具结果摘要的最大长度（完整结果只回灌给模型，观测侧截断防膨胀） */
    private static final int RESULT_SUMMARY_MAX_LENGTH = 500;

    /** 工具结果 → 观测摘要（超长截断） */
    private static String truncateSummary(String content) {
        if (content == null || content.length() <= RESULT_SUMMARY_MAX_LENGTH) {
            return content;
        }
        return content.substring(0, RESULT_SUMMARY_MAX_LENGTH) + "...(已截断)";
    }

    /** 工具成功 / 业务拒绝的结果 → 回灌 JSON */
    private String resultContent(ToolResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", result.success());
        if (result.success()) {
            payload.put("data", result.data());
        } else {
            payload.put("error", result.error());
        }
        return argumentsCodec.serialize(payload);
    }

    /** 框架层错误（未知工具 / 校验失败 / 异常 / 取消等）→ 回灌 JSON */
    private String errorContent(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("error", message);
        return argumentsCodec.serialize(payload);
    }

    /** 高风险确认卡片的人类可读摘要（工具说明 + 参数原文） */
    private static String confirmationSummary(Tool tool, ToolCall call) {
        return "即将执行高风险操作「" + tool.name() + "」（" + tool.description() + "），参数："
                + (call.argumentsJson() == null || call.argumentsJson().isBlank() ? "（无）" : call.argumentsJson());
    }

    /** system 提示 + 历史 */
    private static List<LlmMessage> baseMessages(AgentLoopRequest request) {
        List<LlmMessage> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(LlmMessage.system(request.systemPrompt()));
        }
        messages.addAll(request.history());
        return messages;
    }

    /** 工具轮调用参数：携带 tools；JSON_WITH_TOOLS 模式同时要求 json_object */
    private static LlmRequestOptions toolRoundOptions(AgentLoopRequest request,
                                                      List<ToolDefinition> definitions) {
        return LlmRequestOptions.builder()
                .tools(definitions)
                .toolChoice(ToolChoice.auto())
                .jsonResponseFormat(request.finalJsonMode() == FinalJsonMode.JSON_WITH_TOOLS)
                .build();
    }

    /** 终轮调用参数：不带工具；按模式要求 json_object */
    private static LlmRequestOptions finalRoundOptions(AgentLoopRequest request) {
        return LlmRequestOptions.builder()
                .jsonResponseFormat(request.finalJsonMode() != FinalJsonMode.NONE)
                .build();
    }

    private static Map<String, Tool> toolsByName(AgentLoopRequest request) {
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (Tool tool : request.tools()) {
            byName.put(tool.name(), tool);
        }
        return byName;
    }

    private static int indexOfPendingCall(PendingToolCall pending) {
        List<ToolCall> calls = pending.toolCalls();
        for (int i = 0; i < calls.size(); i++) {
            if (pending.pendingToolCallId().equals(calls.get(i).id())) {
                return i;
            }
        }
        throw new IllegalArgumentException("pendingToolCallId 不在 toolCalls 中: " + pending.pendingToolCallId());
    }

    /** 超时预算：deadline 为 null 表示不限 */
    private static Long deadlineOf(AgentLoopRequest request) {
        return request.timeout() == null ? null : System.nanoTime() + request.timeout().toNanos();
    }

    private static void checkDeadline(AgentLoopRequest request, Long deadline) {
        if (deadline != null && System.nanoTime() - deadline >= 0) {
            throw new AgentLoopTimeoutException(
                    "Agent 执行循环超出整体时间预算（timeout=" + request.timeout() + "）");
        }
    }
}
