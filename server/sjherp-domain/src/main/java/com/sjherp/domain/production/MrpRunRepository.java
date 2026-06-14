package com.sjherp.domain.production;

import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * MRP 运行结果仓储接口（M5-T02，领域层端口）。
 */
public interface MrpRunRepository {

    /** 保存 MRP 运行（新建，含建议行批量插入）。 */
    void save(MrpRun run);

    /** 按文档号查询（含建议行）。 */
    Optional<MrpRun> findByDocNo(String docNo);

    /** 历史运行分页列表（不含建议行明细，只含头信息）。 */
    PageResult<MrpRun> searchHistory(int page, int size);
}
