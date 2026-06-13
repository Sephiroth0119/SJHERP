package com.sjherp.domain.payable;

import java.util.List;
import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 应付账款仓储接口（端口，实现在 infra 层，M3-T07）。
 *
 * <p>应付台账记录只追加、不修改（CLAUDE.md 原则 2：财务记录只可冲销不可改）；本期只有保存
 * 与查询，核销更新在 M4-T03 引入。
 */
public interface AccountsPayableRepository {

    /** 保存一笔应付（新建时回填自增 id；M4-T03 核销时更新已核销金额与状态） */
    void save(AccountsPayable payable);

    /** 按来源单据号查（采购发票号），用于过账幂等防重（同发票重复过账不应重复生成应付） */
    List<AccountsPayable> findBySourceDocNo(String sourceDocNo);

    /** 按 id 查（不存在返回空；M4-T03 核销装载用） */
    Optional<AccountsPayable> findById(long id);

    /** 分页查询（按供应商/状态过滤，可空；按 id 倒序即最近生成在前） */
    PageResult<AccountsPayable> search(AccountsPayableQuery query);
}
