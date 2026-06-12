package com.sjherp.app.audit;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.domain.common.PageResult;
import com.sjherp.infra.persistence.audit.AuditLogEntry;
import com.sjherp.infra.persistence.audit.AuditLogQuery;
import com.sjherp.infra.persistence.audit.AuditLogRepository;

/**
 * 审计日志查询 API（M2-T07，ADMIN/BOSS 限定）：
 * <ul>
 *   <li>GET /api/audit-logs?operator=&amp;action=&amp;targetType=&amp;targetId=&amp;from=&amp;to=&amp;page=&amp;size=
 *       → 200 分页列表（时间倒序，最新在前）；</li>
 *   <li>from/to 为 ISO-8601 时刻（如 2026-06-12T00:00:00Z）；参数非法 → 400 {"error"}；</li>
 *   <li>非 ADMIN/BOSS → 403 {"error":"无权限执行该操作"}（统一文案）。</li>
 * </ul>
 * 审计记录的写入方是 AuditAspect / AuditDomainEventListener，本 API 只读。
 */
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AuditLogRepository repository;

    public AuditLogController(AuditLogRepository repository) {
        this.repository = repository;
    }

    /** 按操作人/动作/目标/时间范围分页查询（仅 ADMIN/BOSS——审计数据含全员操作轨迹） */
    @PreAuthorize("hasAnyRole('ADMIN', 'BOSS')")
    @GetMapping
    public AuditLogPageResponse search(
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 1) {
            throw new IllegalArgumentException("page 必须 >= 1（实际 " + page + "）");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size 必须在 1~" + MAX_PAGE_SIZE + " 之间（实际 " + size + "）");
        }
        PageResult<AuditLogEntry> result = repository.search(new AuditLogQuery(
                operator, action, targetType, targetId,
                parseInstant("from", from), parseInstant("to", to), page, size));
        return AuditLogPageResponse.from(result);
    }

    /** 时间参数解析（非法格式给出友好 400 信息） */
    private static Instant parseInstant(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    name + " 须为 ISO-8601 时刻（如 2026-06-12T00:00:00Z）: " + value);
        }
    }

    // ---------------------------------------------------------------
    // 响应 DTO
    // ---------------------------------------------------------------

    /** 分页响应（契约同既有分页 API：items/total/page/size） */
    public record AuditLogPageResponse(List<AuditLogItem> items, long total, int page, int size) {

        static AuditLogPageResponse from(PageResult<AuditLogEntry> result) {
            return new AuditLogPageResponse(
                    result.items().stream().map(AuditLogItem::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }

    /** 单条审计记录 */
    public record AuditLogItem(long id, String operator, String action, String targetType,
                               Long targetId, String targetCode, String summary,
                               String sessionId, Instant createdAt) {

        static AuditLogItem from(AuditLogEntry entry) {
            return new AuditLogItem(entry.id(), entry.operator(), entry.action(), entry.targetType(),
                    entry.targetId(), entry.targetCode(), entry.summary(),
                    entry.sessionId(), entry.createdAt());
        }
    }
}
