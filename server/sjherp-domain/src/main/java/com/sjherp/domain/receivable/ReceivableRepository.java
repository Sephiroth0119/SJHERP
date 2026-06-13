package com.sjherp.domain.receivable;

import java.util.List;
import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 应收账款仓储接口（端口，实现在 infra 层，M3-T10）。
 */
public interface ReceivableRepository {

    /** 保存应收记录（新建时回填自增 id；M4-T03 核销时更新已核销金额与状态） */
    void save(AccountsReceivable receivable);

    /** 按来源单据号查（销售发票号；用于幂等——同发票不重复生成应收） */
    List<AccountsReceivable> findBySourceDocNo(String sourceDocNo);

    /** 按 id 查（不存在返回空） */
    Optional<AccountsReceivable> findById(long id);

    /** 分页查询（按客户/状态过滤，可空；按 id 倒序即最近创建在前） */
    PageResult<AccountsReceivable> search(ReceivableQuery query);
}
