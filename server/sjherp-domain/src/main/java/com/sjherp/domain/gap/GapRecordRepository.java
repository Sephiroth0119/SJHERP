package com.sjherp.domain.gap;

import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 流程缺口仓储接口（实现位于 infra：JdbcGapRecordRepository）。
 */
public interface GapRecordRepository {

    /** 新建插入（回填自增 id）或按 id 更新 */
    void save(GapRecord record);

    Optional<GapRecord> findById(long id);
    default Optional<GapRecord> findByGapNo(String gapNo) { return Optional.empty(); }

    /** 分页查询（按状态/模块过滤，最新在前） */
    PageResult<GapRecord> search(GapRecordQuery query);
}
