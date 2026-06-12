package com.sjherp.app.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.agent.reply.AgentReply;
import com.sjherp.agent.session.AgentMessage;
import com.sjherp.agent.session.AgentSession;
import com.sjherp.agent.session.MessageRole;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.infra.agent.AgentReplyJsonCodec;

/**
 * 会话 API（前后端契约，前端并行对接中，路径与字段不得擅改）：
 * <ul>
 *   <li>POST /api/chat/sessions → 201 {"sessionId", "createdAt"}</li>
 *   <li>GET  /api/chat/sessions/{id} → 200 {"sessionId", "status", "messages": [...]}，不存在 404 {"error"}</li>
 *   <li>POST /api/chat/sessions/{id}/messages → 200，响应体直接是 AgentReply 协议 JSON（v0.1）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/chat/sessions")
public class ChatSessionController {

    private final ChatService chatService;
    private final AgentReplyJsonCodec codec;

    public ChatSessionController(ChatService chatService, AgentReplyJsonCodec codec) {
        this.chatService = chatService;
        this.codec = codec;
    }

    /** 创建会话（归属当前登录用户：user_id 落库为 sys_user.id，M2-T05） */
    @PostMapping
    public ResponseEntity<Map<String, String>> createSession() {
        AgentSession session = chatService.createSession(CurrentUser.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "sessionId", session.getSessionId(),
                "createdAt", session.getCreatedAt().toString()));
    }

    /** 查询会话（消息按 seq 升序回放） */
    @GetMapping("/{id}")
    public Map<String, Object> getSession(@PathVariable String id) {
        AgentSession session = chatService.getSession(id);

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
        body.put("status", session.getStatus().name().toLowerCase(Locale.ROOT));
        body.put("messages", messages);
        return body;
    }

    /** 发送消息（text / optionId / formId 三选一），响应体直接是 AgentReply 协议 JSON */
    @PostMapping(value = "/{id}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public String sendMessage(@PathVariable String id, @RequestBody SendMessageRequest request) {
        AgentReply reply = chatService.handleMessage(id, request);
        // 用协议编解码器序列化（枚举小写、可选字段省略），不走 Spring 默认 ObjectMapper
        return codec.toJson(reply);
    }

    /** 会话不存在 → 404 {"error": "..."} */
    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(SessionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    /** 非法请求（缺少 text/optionId/formId、选项不存在等）→ 400 {"error": "..."} */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
