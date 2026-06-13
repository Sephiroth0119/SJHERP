package com.sjherp.domain.gl;

import java.util.List;
import java.util.Optional;

/**
 * 会计科目仓储接口（端口，实现在 infra 层，M4-T01）。
 *
 * <p>科目为单表档案（account），save 按编码 insert/update（新建回填自增 id）。
 * 预置科目走 V19 迁移 INSERT，启动时即就位（拆解 §2 决策）。
 */
public interface AccountRepository {

    /** 保存科目档案（新建时落库后回填自增 id；已存在时更新启停与审计字段） */
    void save(Account account);

    /** 按编码查（不存在返回空） */
    Optional<Account> findByCode(String code);

    /** 全部科目（按编码升序，供科目表展示） */
    List<Account> findAll();

    /** 全部末级科目（仅末级可挂账，供凭证录入下拉） */
    List<Account> findLeaf();

    /** 编码唯一性校验用（AccountService 创建前置检查；数据库唯一键兜底） */
    boolean existsByCode(String code);
}
