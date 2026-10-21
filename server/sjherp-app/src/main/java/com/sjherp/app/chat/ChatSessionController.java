package com.sjherp.app.chat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.sjherp.agent.reply.AgentReply;
import com.sjherp.agent.reply.AgentStreamEvent;
import com.sjherp.agent.session.AgentMessage;
import com.sjherp.agent.session.AgentSession;
import com.sjherp.agent.session.AgentSessionSummary;
import com.sjherp.agent.session.MessageRole;
import com.sjherp.agent.session.SessionStatus;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.infra.agent.AgentReplyJsonCodec;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 会话 API（前后端契约，前端并行对接中，路径与字段不得擅改）：
 * <ul>
 *   <li>POST /api/chat/sessions → 201 {"sessionId", "createdAt"}</li>
 *   <li>GET  /api/chat/sessions → 200 {"items": [{sessionId, title, status, createdAt, updatedAt}]}，仅本人</li>
 *   <li>GET  /api/chat/sessions/{id} → 200 详情（含 title/status/messages），不存在或非本人 404 {"error"}</li>
 *   <li>PUT  /api/chat/sessions/{id}/title → 200 重命名；非本人 404</li>
 *   <li>POST /api/chat/sessions/{id}/archive → 200 归档（CLOSED，无物理删除）；非本人 404</li>
 *   <li>POST /api/chat/sessions/{id}/messages
 *       Accept: application/json → 200，完整 AgentReply；已归档 409；非本人 404</li>
 *   <li>POST /api/chat/sessions/{id}/messages
 *       Accept: text/event-stream → SSE 流式分片（协议 v0.2）；最终 complete 可还原完整 AgentReply</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/chat/sessions")
public class ChatSessionController {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionController.class);

    /** SSE 心跳间隔：避免长时间工具轮无输出被代理断开 */
    private static final long HEARTBEAT_SECONDS = 15;

    private final ChatService chatService;
    private final AgentReplyJsonCodec codec;
    private final Executor chatStreamExecutor;
    private final ScheduledExecutorService chatHeartbeatScheduler;
    private final long sseTimeoutMs;

    public ChatSessionController(ChatService chatService, AgentReplyJsonCodec codec,
                                 @Qualifier("chatStreamExecutor") Executor chatStreamExecutor,
                                 @Qualifier("chatHeartbeatScheduler") ScheduledExecutorService chatHeartbeatScheduler,
                                 @Value("${sjherp.agent.loop-timeout-seconds:300}") long loopTimeoutSeconds) {
        this.chatService = chatService;
        this.codec = codec;
        this.chatStreamExecutor = chatStreamExecutor;
        this.chatHeartbeatScheduler = chatHeartbeatScheduler;
        // 比循环预算多 30s，给落库与 complete 事件留余量
        this.sseTimeoutMs = TimeUnit.SECONDS.toMillis(Math.max(loopTimeoutSeconds, 1) + 30);
    }

    /** 创建会话（归属当前登录用户：user_id 落库为 sys_user.id，M2-T05） */
    @PostMapping
    public ResponseEntity<Map<String, String>> createSession() {
        AgentSession session = chatService.createSession(CurrentUser.userId());
        Map<String, String> body = new LinkedHashMap<>();
        body.put("sessionId", session.getSessionId());
        body.put("createdAt", session.getCreatedAt().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 本人会话列表（不含消息正文；按 updatedAt 降序） */
    @GetMapping
    public Map<String, Object> listSessions() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (AgentSessionSummary summary : chatService.listSessions(CurrentUser.userId())) {
            items.add(toSummaryDto(summary));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        return body;
    }

    /** 查询会话（消息按 seq 升序回放；仅会话归属人可见，非本人 404） */
    @GetMapping("/{id}")
    public Map<String, Object> getSession(@PathVariable String id) {
        AgentSession session = chatService.getSession(id, CurrentUser.userId());

        List<Map<String, Object>> messages = new ArrayList<>();
        int seq = 0;
        for (AgentMessage message : session.getMessages()) {
            seq++;
            // LinkedHashMap：允许 null 值（契约要求 text/reply 显式为 null）且保持字段顺序
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", seq); // 消息 id 即会话内 seq（稳定、按升序）
            if (message.role() == MessageRole.ASSISTANT) {
                dto.put("role", "agent");
                dto.put("text", null);
                // 落库的就是协议 JSON，原样嵌入，保证与发送时字节级一致
                dto.put("reply", codec.toTree(message.content()));
            } else {
                dto.put("role", "user");
                dto.put("text", message.content());
                dto.put("reply", null);
            }
            dto.put("createdAt", message.createdAt().toString());
            messages.add(dto);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", session.getSessionId());
        body.put("title", session.getTitle());
        body.put("status", session.getStatus().name().toLowerCase(Locale.ROOT));
        body.put("createdAt", session.getCreatedAt().toString());
        body.put("updatedAt", session.getUpdatedAt().toString());
        body.put("messages", messages);
        return body;
    }

    /** 重命名会话；仅归属人，非本人 404 */
    @PutMapping("/{id}/title")
    public Map<String, Object> renameSession(@PathVariable String id,
                                             @RequestBody RenameSessionRequest request) {
        AgentSession session = chatService.renameSession(
                id, CurrentUser.userId(), request == null ? null : request.title());
        return toSessionHeader(session);
    }

    /** 归档会话（CLOSED，无物理删除）；仅归属人，非本人 404；已归档幂等 */
    @PostMapping("/{id}/archive")
    public Map<String, Object> archiveSession(@PathVariable String id) {
        AgentSession session = chatService.archiveSession(id, CurrentUser.userId());
        return toSessionHeader(session);
    }

    /**
     * 发送消息（text / optionId / formId 三选一），响应体直接是完整 AgentReply。
     * 非流式客户端继续走本入口，不必理解 v0.2 分片。已归档 409；非本人 404。
     */
    @PostMapping(value = "/{id}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public String sendMessage(@PathVariable String id, @RequestBody SendMessageRequest request) {
        AgentReply reply = chatService.handleMessage(id, CurrentUser.userId(), request);
        return codec.toJson(reply);
    }

    /**
     * 流式发送消息：LLM 边生成边推 delta，结束后 complete 携带完整 AgentReply。
     * 高风险 HITL 仍由框架拦截，确认卡片只出现在 complete 的 options 中。
     * 打开流之前做归属/归档校验：越权 404、已归档 409，均走 JSON 而非 SSE。
     */
    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageStream(@PathVariable String id, @RequestBody SendMessageRequest request,
                                        HttpServletResponse response) {
        String userId = CurrentUser.userId();
        AgentSession session = chatService.getSession(id, userId);
        if (session.getStatus() == SessionStatus.CLOSED) {
            throw new SessionClosedException(id);
        }

        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");

        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        AtomicBoolean finished = new AtomicBoolean(false);
        ScheduledFuture<?> heartbeat = chatHeartbeatScheduler.scheduleAtFixedRate(() -> {
            if (finished.get()) {
                return;
            }
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().comment("ping"));
                }
            } catch (IOException ignored) {
                finished.set(true);
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        Runnable stopHeartbeat = () -> {
            finished.set(true);
            heartbeat.cancel(false);
        };
        emitter.onCompletion(stopHeartbeat);
        emitter.onTimeout(stopHeartbeat);
        emitter.onError(error -> stopHeartbeat.run());

        chatStreamExecutor.execute(() -> {
            try {
                chatService.handleMessageStreaming(id, userId, request, event -> sendEvent(emitter, event));
                emitter.complete();
            } catch (SessionNotFoundException | SessionClosedException | IllegalArgumentException e) {
                sendEventQuietly(emitter, AgentStreamEvent.error(e.getMessage()));
                emitter.complete();
            } catch (RuntimeException e) {
                log.error("聊天 SSE 处理失败（sessionId={}）", id, e);
                String message = e.getMessage() == null ? "聊天处理失败，请稍后重试" : e.getMessage();
                sendEventQuietly(emitter, AgentStreamEvent.error(message));
                emitter.complete();
            } finally {
                stopHeartbeat.run();
            }
        });
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, AgentStreamEvent event) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event()
                        .name(event.type().json())
                        .data(codec.toStreamJson(event), MediaType.APPLICATION_JSON));
            }
        } catch (IOException e) {
            throw new IllegalStateException("SSE 推送失败", e);
        }
    }

    private void sendEventQuietly(SseEmitter emitter, AgentStreamEvent event) {
        try {
            sendEvent(emitter, event);
        } catch (RuntimeException e) {
            log.debug("SSE 错误事件推送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    private static Map<String, Object> toSessionHeader(AgentSession session) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", session.getSessionId());
        body.put("title", session.getTitle());
        body.put("status", session.getStatus().name().toLowerCase(Locale.ROOT));
        body.put("createdAt", session.getCreatedAt().toString());
        body.put("updatedAt", session.getUpdatedAt().toString());
        return body;
    }

    private static Map<String, Object> toSummaryDto(AgentSessionSummary summary) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("sessionId", summary.sessionId());
        dto.put("title", summary.title());
        dto.put("status", summary.status().name().toLowerCase(Locale.ROOT));
        dto.put("createdAt", summary.createdAt().toString());
        dto.put("updatedAt", summary.updatedAt().toString());
        return dto;
    }

    /** 会话不存在 → 404 {"error": "..."} */
    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(SessionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    /** 已归档会话拒绝继续发送 → 409 {"error": "..."} */
    @ExceptionHandler(SessionClosedException.class)
    public ResponseEntity<Map<String, String>> handleClosed(SessionClosedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    /** 非法请求（缺少 text/optionId/formId、选项不存在、空标题等）→ 400 {"error": "..."} */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
