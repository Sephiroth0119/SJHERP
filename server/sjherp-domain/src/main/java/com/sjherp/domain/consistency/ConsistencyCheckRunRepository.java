package com.sjherp.domain.consistency;

import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/** 一致性检查运行报告的持久化端口；不提供物理删除。 */
public interface ConsistencyCheckRunRepository {

    void save(ConsistencyCheckRun run);

    Optional<ConsistencyCheckRun> findByRunNo(long tenantId, String runNo);

    PageResult<ConsistencyCheckRun> search(long tenantId, ConsistencyRunQuery query);
}
