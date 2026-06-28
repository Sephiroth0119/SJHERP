package com.sjherp.domain.production;

import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 领料单仓储接口（M5-T04）。实现由 infra 层提供。
 */
public interface MaterialIssueRepository {

    /** 保存领料单（新建时插入，已存在时更新） */
    void save(MaterialIssue materialIssue);

    /** 按单号查询（不存在返回 empty） */
    Optional<MaterialIssue> findByDocNo(String docNo);

    /** 分页查询（支持工单号/状态过滤） */
    PageResult<MaterialIssue> search(MaterialIssueQuery query);
}
