package com.sjherp.infra.persistence.audit;

import java.time.Instant;
import java.util.Objects;

/**
 * 审计日志记录（audit_log 表一行，M2-T07）。只插入不更新（可审计原则）。
 *
 * @param id         主键（插入前为 null）
 * @param operator   操作人：人工=登录名；Agent=agent:&lt;userId&gt;
 * @param action     动作标识（业务语义），如 product.create / customer.disable
 * @param targetType 目标类型，如 product / customer / user / document
 * @param targetId   目标主键（领域事件类记录可空）
 * @param targetCode 目标业务编码（编码/登录名/单据号等）
 * @param summary    变更摘要（更新类为「变更前 → 变更后」）
 * @param sessionId  Agent 操作来源会话 id（人工操作为空）
 * @param createdAt  记录时间（UTC）
 */
public record AuditLogEntry(Long id, String operator, String action, String targetType,
                            Long targetId, String targetCode, String summary,
                            String sessionId, Instant createdAt) {

    public AuditLogEntry {
        Objects.requireNonNull(operator, "operator 不能为空");
        Objects.requireNonNull(action, "action 不能为空");
        Objects.requireNonNull(targetType, "targetType 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
    }
}
