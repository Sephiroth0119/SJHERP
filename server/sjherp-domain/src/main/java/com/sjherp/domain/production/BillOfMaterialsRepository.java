package com.sjherp.domain.production;

import java.util.List;
import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/**
 * BOM 仓储接口（端口，实现在 infra 层）。
 *
 * <p>聚合整体读写：save 同时持久化 header + lines（先删后插）；
 * findById 回带完整行列表。
 */
public interface BillOfMaterialsRepository {

    /** 保存聚合（新建时落库后回填自增 id；行列表整体替换） */
    void save(BillOfMaterials bom);

    Optional<BillOfMaterials> findById(long id);

    /**
     * 按（productId，version）查找（自然键唯一性校验用）。
     */
    Optional<BillOfMaterials> findByProductAndVersion(long productId, int version);

    /** 查找同 productId 的其他所有 ENABLED 版本（版本切换时停用用） */
    List<BillOfMaterials> findEnabledByProductId(long productId);

    /** 分页查询（按 productId/status 过滤，按 id 倒序） */
    PageResult<BillOfMaterials> search(BillOfMaterialsQuery query);

    /**
     * 按 productId 查找当前 ENABLED 版本（至多一条）。
     * T02 MRP 展开/T06 成本归集消费此方法。
     */
    Optional<BillOfMaterials> findActiveByProductId(long productId);

    /**
     * 查找给定父件 productId 的 ENABLED BOM 的所有直接子件 productId 列表。
     * 仅用于保存时环检测递归遍历。
     */
    List<Long> findChildProductIds(long parentProductId);

    /** 是否存在（productId, version）组合（唯一性检查） */
    boolean existsByProductAndVersion(long productId, int version);
}
