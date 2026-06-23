package com.sjherp.domain.production;

import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 退料单仓储接口（M5-T04）。实现由 infra 层提供。
 */
public interface MaterialReturnRepository {

    /** 保存退料单（新建时插入，已存在时更新） */
    void save(MaterialReturn materialReturn);

    /** 按单号查询（不存在返回 empty） */
    Optional<MaterialReturn> findByDocNo(String docNo);

    /** 分页查询（支持原领料单号/状态过滤） */
    PageResult<MaterialReturn> search(MaterialReturnQuery query);
}
