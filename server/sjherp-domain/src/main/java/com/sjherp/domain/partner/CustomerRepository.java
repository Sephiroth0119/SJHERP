package com.sjherp.domain.partner;

import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 客户仓储接口（端口，实现在 infra 层）。
 */
public interface CustomerRepository {

    /** 保存聚合（新建时落库后回填自增 id） */
    void save(Customer customer);

    Optional<Customer> findById(long id);

    /** 编码唯一性校验用（CustomerService 创建/更新前置检查；数据库唯一键兜底） */
    boolean existsByCode(String code);

    /** 分页查询：关键字模糊匹配编码/名称/联系人/电话，可按状态过滤，按 id 倒序 */
    PageResult<Customer> search(CustomerQuery query);
}
