package com.sjherp.domain.consistency;

import java.time.Instant;
import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/** 一致性检查运行报告的持久化端口；不提供物理删除。 */
public interface ConsistencyCheckRunRepository {

    void save(ConsistencyCheckRun run);

    Optional<ConsistencyCheckRun> findByRunNo(long tenantId, String runNo);

    /** 查询指定 UTC 时间半开区间内最近一次运行，供登录用户通过 Agent 回看历史报告。 */
    Optional<ConsistencyCheckRun> findLatestByCompletedAtBetween(long tenantId,
                                                                  Instant startInclusive,
                                                                  Instant endExclusive);

    PageResult<ConsistencyCheckRun> search(long tenantId, ConsistencyRunQuery query);
}
