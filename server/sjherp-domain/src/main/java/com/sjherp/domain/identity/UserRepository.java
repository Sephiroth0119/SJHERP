package com.sjherp.domain.identity;

import java.util.List;
import java.util.Optional;

/**
 * 用户仓储接口（infra 提供 MySQL 实现）。
 */
public interface UserRepository {

    /** 保存（无 id 插入并回填自增 id，有 id 更新） */
    void save(User user);

    Optional<User> findById(long id);

    /** 按登录名精确查找（登录入口） */
    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    /** 全量列表（小企业用户数量级很小，不做分页），按 id 升序 */
    List<User> findAll();
}
