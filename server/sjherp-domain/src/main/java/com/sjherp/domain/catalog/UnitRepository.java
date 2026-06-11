package com.sjherp.domain.catalog;

import java.util.List;
import java.util.Optional;

/**
 * 计量单位仓储接口（端口，实现在 infra 层）。
 */
public interface UnitRepository {

    /** 保存（新建时落库后回填自增 id） */
    void save(Unit unit);

    Optional<Unit> findById(long id);

    /** 名称唯一性校验用 */
    Optional<Unit> findByName(String name);

    List<Unit> findAll();

    /** 物理删除（仅允许无商品引用时，由 UnitService 把关） */
    void deleteById(long id);
}
