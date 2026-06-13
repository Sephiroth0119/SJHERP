package com.sjherp.domain.fund;

import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 资金账户仓储接口（端口，实现在 infra 层，M4-T04a）。
 */
public interface PaymentAccountRepository {

    /** 保存聚合（新建时落库后回填自增 id） */
    void save(PaymentAccount account);

    Optional<PaymentAccount> findById(long id);

    /** 按编码查（收/付款单过账取 glAccountCode 用；不存在返回空） */
    Optional<PaymentAccount> findByCode(String code);

    /** 编码唯一性校验用（PaymentAccountService 创建/更新前置检查；数据库唯一键兜底） */
    boolean existsByCode(String code);

    /** 分页查询：关键字模糊匹配编码/名称/开户行，可按状态过滤，按 id 倒序 */
    PageResult<PaymentAccount> search(PaymentAccountQuery query);
}
