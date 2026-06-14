package com.sjherp.domain.production;

import java.util.List;
import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * 工艺路线仓储接口（端口，实现在 infra 层）。
 */
public interface RoutingRepository {

    /** 保存聚合（新建时落库后回填自增 id；工序列表整体替换） */
    void save(Routing routing);

    Optional<Routing> findById(long id);

    Optional<Routing> findByProductAndVersion(long productId, int version);

    /** 查找同 productId 的所有 ENABLED 版本（版本切换时停用用） */
    List<Routing> findEnabledByProductId(long productId);

    /** 分页查询 */
    PageResult<Routing> search(RoutingQuery query);

    /**
     * 按 productId 查找当前 ENABLED 版本（至多一条）。
     * T06 成本归集消费此方法。
     */
    Optional<Routing> findActiveByProductId(long productId);

    /** 是否存在（productId, version）组合 */
    boolean existsByProductAndVersion(long productId, int version);
}
