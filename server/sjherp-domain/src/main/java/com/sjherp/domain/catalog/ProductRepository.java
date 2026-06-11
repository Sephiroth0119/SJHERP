package com.sjherp.domain.catalog;

import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 商品仓储接口（端口，实现在 infra 层）。
 *
 * <p>聚合整体读写：save 同时持久化商品行与换算表（同事务），
 * findById 回带完整换算表。
 */
public interface ProductRepository {

    /** 保存聚合（新建时落库后回填自增 id；换算表整体替换） */
    void save(Product product);

    Optional<Product> findById(long id);

    /** 编码唯一性校验用（ProductService 创建/更新前置检查；数据库唯一键兜底） */
    boolean existsByCode(String code);

    /** 分页查询：关键字模糊匹配编码/名称/条码，可按状态过滤，按 id 倒序 */
    PageResult<Product> search(ProductQuery query);

    /** 是否有商品引用该类目（类目删除保护） */
    boolean existsByCategoryId(long categoryId);

    /** 是否有商品引用该单位——基本单位或换算单位（单位删除保护） */
    boolean existsByUnitId(long unitId);
}
