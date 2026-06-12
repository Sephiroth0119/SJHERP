package com.sjherp.infra.persistence.audit;

import java.time.Instant;

/**
 * 审计日志查询条件（M2-T07，GET /api/audit-logs 的筛选参数）。
 *
 * @param operator   操作人精确匹配（含 agent: 前缀），可空
 * @param action     动作标识精确匹配，可空
 * @param targetType 目标类型精确匹配，可空
 * @param targetId   目标主键精确匹配，可空
 * @param from       记录时间下界（含），可空
 * @param to         记录时间上界（含），可空
 * @param page       页码（从 1 开始）
 * @param size       每页条数
 */
public record AuditLogQuery(String operator, String action, String targetType, Long targetId,
                            Instant from, Instant to, int page, int size) {

    public AuditLogQuery {
        if (page < 1) {
            throw new IllegalArgumentException("page 必须 >= 1: " + page);
        }
        if (size < 1) {
            throw new IllegalArgumentException("size 必须 >= 1: " + size);
        }
    }
}
