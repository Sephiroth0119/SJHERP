package com.sjherp.domain.catalog;

import java.util.List;
import java.util.Optional;

/**
 * 商品类目仓储接口（端口，实现在 infra 层）。
 */
public interface CategoryRepository {

    /** 保存（新建时落库后回填自增 id） */
    void save(Category category);

    Optional<Category> findById(long id);

    /** 名称全局唯一性校验用（小企业从简：类目名称不分层级全局唯一） */
    Optional<Category> findByName(String name);

    /** 全量列表（树由调用方按 parentId 组装；小企业类目量级很小） */
    List<Category> findAll();

    /** 是否存在子类目（删除保护） */
    boolean existsByParentId(long parentId);

    /** 物理删除（仅允许无子类目且无商品引用时，由 CategoryService 把关） */
    void deleteById(long id);
}
