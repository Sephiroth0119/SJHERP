package com.sjherp.app.invocation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.chat.ChatService;
import com.sjherp.app.invocation.AgentInvocationDtos.InvocationPageResponse;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.infra.persistence.invocation.AgentInvocationRepository;

/**
 * Agent 调用观测查询 API（M1-T06，开发/运营侧用，X-6 成本看板数据源）：
 * <ul>
 *   <li>GET /api/agent/invocations?sessionId=xxx&amp;page=&amp;size= → 200 分页列表（时间倒序），
 *       含本会话累计 token 汇总字段（totalPromptTokens / totalCompletionTokens）；</li>
 *   <li>参数缺失/非法 → 400 {"error"}；</li>
 *   <li>会话不存在或不属于当前用户 → 404 {"error"}（P2 越权修复，统一按「不存在」处理，
 *       不泄露会话存在性；ADMIN 不豁免，管理侧查询留给以后专门接口）。</li>
 * </ul>
 * 观测记录的写入方是 PersistingAgentInvocationListener（随对话自动落库），本 API 只读。
 */
@RestController
@RequestMapping("/api/agent/invocations")
public class AgentInvocationController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AgentInvocationRepository repository;
    private final ChatService chatService;

    public AgentInvocationController(AgentInvocationRepository repository, ChatService chatService) {
        this.repository = repository;
        this.chatService = chatService;
    }

    /** 按会话分页查询（created_at 倒序，最新在前；仅会话归属人可查） */
    @GetMapping
    public InvocationPageResponse list(@RequestParam String sessionId,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (page < 1) {
            throw new IllegalArgumentException("page 必须 >= 1（实际 " + page + "）");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size 必须在 1~" + MAX_PAGE_SIZE + " 之间（实际 " + size + "）");
        }
        // 归属校验复用 ChatService 的同一处逻辑：不存在 / 非本人统一抛 SessionNotFoundException → 404
        chatService.getSession(sessionId, CurrentUser.userId());
        return InvocationPageResponse.of(
                repository.findBySession(sessionId, page, size),
                repository.sumTokens(sessionId));
    }
}
