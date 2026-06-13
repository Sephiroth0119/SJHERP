package com.sjherp.domain.gl;

import java.util.List;
import java.util.Optional;

/**
 * 会计期间仓储接口（端口，实现在 infra 层，M4-T01）。
 *
 * <p>账期为单表档案（accounting_period），save 按账期键 upsert（新建回填自增 id）。
 */
public interface AccountingPeriodRepository {

    /** 保存账期（新建时落库后回填自增 id；已存在时更新状态/关账标记/审计字段） */
    void save(AccountingPeriod period);

    /** 按账期键查（不存在返回空） */
    Optional<AccountingPeriod> findByPeriod(String period);

    /** 全部账期（按账期键升序，供账期列表展示） */
    List<AccountingPeriod> findAll();
}
