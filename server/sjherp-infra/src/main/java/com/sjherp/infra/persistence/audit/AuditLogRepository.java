package com.sjherp.infra.persistence.audit;

import com.sjherp.domain.common.PageResult;

/**
 * 审计日志仓储（M2-T07）。只插入与查询：审计记录不可修改/删除（CLAUDE.md 原则 3）。
 */
public interface AuditLogRepository {

    /** 插入一条审计记录（调用方负责失败兜底：审计写入失败不得阻塞业务） */
    void insert(AuditLogEntry entry);

    /** 按条件分页查询（created_at 倒序，最新在前） */
    PageResult<AuditLogEntry> search(AuditLogQuery query);
}
