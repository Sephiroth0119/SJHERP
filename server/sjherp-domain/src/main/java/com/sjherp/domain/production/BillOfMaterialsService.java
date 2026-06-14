package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductRepository;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;

/**
 * BOM 领域服务（唯一写入口，M5-T01）。
 *
 * <p>不可妥协原则 1：所有 BOM 写操作经此服务，不可绕过。
 * <p>不可妥协原则 3：每个写方法标注 {@link Audited}，审计切面自动记录。
 */
public class BillOfMaterialsService {

    private final BillOfMaterialsRepository bomRepository;
    private final ProductRepository productRepository;

    public BillOfMaterialsService(BillOfMaterialsRepository bomRepository,
                                  ProductRepository productRepository) {
        this.bomRepository = bomRepository;
        this.productRepository = productRepository;
    }

    // ================================================================ 写操作（@Audited）

    /**
     * 创建 BOM（默认 ENABLED，自动停用同产品其他启用版本）。
     *
     * <p>校验顺序：父件存在且启用 → 版本唯一 → 子件存在且启用 → 环检测 → 落库 → 停用其他版本。
     */
    @Audited(action = "bom.create", targetType = "BOM")
    public BillOfMaterials create(BillOfMaterialsCommand command, String operator) {
        Objects.requireNonNull(command, "命令不能为空");
        validateParentProduct(command.productId());
        if (bomRepository.existsByProductAndVersion(command.productId(), command.version())) {
            throw new IllegalArgumentException(
                    "该产品已存在 v" + command.version() + " 的 BOM，请选择其他版本号: productId=" + command.productId());
        }

        List<BomLine> lines = buildAndValidateLines(command.productId(), command.lines());
        checkCycleOnSave(command.productId(), command.lines());

        // 关键顺序：新建默认 ENABLED，必须先停用同产品其他 ENABLED 版本再插入——
        // 否则 INSERT 这一步即撞 DB active_flag 唯一索引（同产品至多一启用版本）抛约束异常（评审 P0）。
        // 新 BOM 尚未持久化（无 id），传 0L 不匹配任何真实 id → 停用该产品当前全部 ENABLED 版本。
        disableOtherEnabledVersions(command.productId(), 0L, operator);

        BillOfMaterials bom = new BillOfMaterials(
                command.productId(), command.version(), command.remark(), lines, operator);
        bomRepository.save(bom);
        return bom;
    }

    /**
     * 更新 BOM 内容（行列表整体替换，version 不可变）。
     * 可更新 ENABLED 或 DISABLED 的 BOM（停用版本也允许编辑以备重新启用）。
     */
    @Audited(action = "bom.update", targetType = "BOM")
    public BillOfMaterials update(long id, BillOfMaterialsCommand command, String operator) {
        BillOfMaterials bom = bomRepository.findById(id)
                .orElseThrow(() -> BillOfMaterialsNotFoundException.byId(id));

        List<BomLine> lines = buildAndValidateLines(bom.getProductId(), command.lines());
        checkCycleOnSave(bom.getProductId(), command.lines());

        bom.update(command.remark(), lines, operator);
        bomRepository.save(bom);
        return bom;
    }

    /**
     * 启用 BOM（同事务先停用同产品其他 ENABLED 版本，再启用目标）。
     * 数据库 active_flag 唯一索引兜底保证至多一条 ENABLED。
     */
    @Audited(action = "bom.enable", targetType = "BOM")
    public BillOfMaterials enable(long id, String operator) {
        BillOfMaterials bom = bomRepository.findById(id)
                .orElseThrow(() -> BillOfMaterialsNotFoundException.byId(id));
        disableOtherEnabledVersions(bom.getProductId(), id, operator);
        bom.enable(operator);
        bomRepository.save(bom);
        return bom;
    }

    /** 停用 BOM */
    @Audited(action = "bom.disable", targetType = "BOM")
    public BillOfMaterials disable(long id, String operator) {
        BillOfMaterials bom = bomRepository.findById(id)
                .orElseThrow(() -> BillOfMaterialsNotFoundException.byId(id));
        bom.disable(operator);
        bomRepository.save(bom);
        return bom;
    }

    // ================================================================ 读操作

    /** 按 id 查询（不存在抛 404） */
    public BillOfMaterials get(long id) {
        return bomRepository.findById(id)
                .orElseThrow(() -> BillOfMaterialsNotFoundException.byId(id));
    }

    /** 分页搜索 */
    public PageResult<BillOfMaterials> search(BillOfMaterialsQuery query) {
        return bomRepository.search(query);
    }

    /**
     * BOM 递归展开（只读，T02 MRP 展开消费）。
     *
     * <p>展开策略：找 productId 的 active BOM → 逐行计算毛需求 → 递归展开子件。
     * 若子件无 active BOM 则为叶节点停止递归。
     *
     * <p>环检测（运行时，防御历史脏数据）：携带访问路径集合，重复入栈抛 {@link BomCycleException}。
     *
     * @param productId 父件商品 id
     * @param quantity  父件需求数量（&gt; 0）
     * @return 展开结果树（若无 active BOM 则 nodes 为空）
     */
    public BomExplosion explode(long productId, BigDecimal quantity) {
        Objects.requireNonNull(quantity, "展开数量不能为空");
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("展开数量必须大于 0: " + quantity.toPlainString());
        }
        Set<Long> visitedPath = new HashSet<>();
        visitedPath.add(productId);
        List<BomExplosionNode> nodes = explodeLevel(productId, quantity, 1, visitedPath);
        return new BomExplosion(productId, quantity, nodes);
    }

    // ================================================================ 私有辅助

    /** 递归展开一层，返回子件节点列表 */
    private List<BomExplosionNode> explodeLevel(long productId, BigDecimal parentQty,
                                                 int level, Set<Long> visitedPath) {
        return bomRepository.findActiveByProductId(productId)
                .map(bom -> {
                    List<BomExplosionNode> result = new ArrayList<>();
                    for (BomLine line : bom.getLines()) {
                        long childId = line.childProductId();
                        if (visitedPath.contains(childId)) {
                            throw new BomCycleException(
                                    "BOM 展开发现环形依赖: productId=" + childId
                                            + " 已在访问路径中（历史脏数据或跨版本成环）");
                        }
                        // 子件净需求 = 父件需求 × 行用量；毛需求再按损耗率加成（grossQuantity 只乘 1+scrapRate）
                        BigDecimal childNetQty = parentQty.multiply(line.quantity());
                        BigDecimal childGrossQty = line.grossQuantity(childNetQty);
                        Set<Long> childPath = new HashSet<>(visitedPath);
                        childPath.add(childId);
                        List<BomExplosionNode> children = explodeLevel(childId, childGrossQty, level + 1, childPath);
                        result.add(new BomExplosionNode(childId, childGrossQty, line.unitId(), level, children));
                    }
                    return result;
                })
                .orElse(List.of()); // 无 active BOM：叶节点，停止递归
    }

    /** 停用同 productId 下其他 ENABLED 版本（排除当前 id） */
    private void disableOtherEnabledVersions(long productId, long excludeId, String operator) {
        List<BillOfMaterials> others = bomRepository.findEnabledByProductId(productId);
        for (BillOfMaterials other : others) {
            if (!other.getId().equals(excludeId)) {
                other.disable(operator);
                bomRepository.save(other);
            }
        }
    }

    /** 校验父件商品存在且已启用 */
    private void validateParentProduct(long productId) {
        Product parent = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("父件商品不存在: id=" + productId));
        if (parent.getStatus() != com.sjherp.domain.common.ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("父件商品已停用，不能建立 BOM: id=" + productId);
        }
    }

    /** 校验所有子件商品存在且已启用，并构建 BomLine 值对象列表 */
    private List<BomLine> buildAndValidateLines(long parentProductId, List<BomLineCommand> lineCommands) {
        Objects.requireNonNull(lineCommands, "BOM 行列表不能为空");
        if (lineCommands.isEmpty()) {
            throw new IllegalArgumentException("BOM 至少需要一行子件");
        }
        List<BomLine> lines = new ArrayList<>();
        for (BomLineCommand cmd : lineCommands) {
            Product child = productRepository.findById(cmd.childProductId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "子件商品不存在: id=" + cmd.childProductId()));
            if (child.getStatus() != ArchiveStatus.ENABLED) {
                throw new IllegalArgumentException(
                        "子件商品已停用，不能加入 BOM: id=" + cmd.childProductId());
            }
            lines.add(new BomLine(cmd.childProductId(),
                    cmd.quantity(),
                    cmd.scrapRate() != null ? cmd.scrapRate() : BigDecimal.ZERO,
                    cmd.unitId()));
        }
        return lines;
    }

    /**
     * 保存时环检测：对每个子件，递归向上确认其 active BOM 子树不含当前父件。
     *
     * <p>算法：DFS，从子件出发，沿 findChildProductIds 递归向下，
     * 若发现 parentProductId 则说明有环（因为已存在 child→...→parent 路径，
     * 再加上本次 parent→child 即成环）。
     */
    private void checkCycleOnSave(long parentProductId, List<BomLineCommand> lineCommands) {
        for (BomLineCommand cmd : lineCommands) {
            if (cmd.childProductId() == parentProductId) {
                // 直接自引用，聚合根构造器也会拒绝，此处提前快速路径
                throw new BomCycleException(
                        "BOM 行不能引用父件自身: productId=" + parentProductId);
            }
            Set<Long> visited = new HashSet<>();
            visited.add(parentProductId); // 将父件加入已访问
            checkNoCycleFrom(cmd.childProductId(), parentProductId, visited);
        }
    }

    /**
     * 从 startProductId 出发，DFS 遍历 active BOM 子件树，
     * 若发现 targetProductId 则说明有环。
     */
    private void checkNoCycleFrom(long startProductId, long targetProductId, Set<Long> visited) {
        if (visited.contains(startProductId)) {
            if (startProductId == targetProductId) {
                throw new BomCycleException(
                        "创建 BOM 会形成环形依赖: productId=" + targetProductId
                                + " 已在子件 " + startProductId + " 的 BOM 树中");
            }
            return; // 已访问但非目标，跳过（防重复 DFS，非环）
        }
        visited.add(startProductId);
        List<Long> children = bomRepository.findChildProductIds(startProductId);
        for (long childId : children) {
            if (childId == targetProductId) {
                throw new BomCycleException(
                        "创建 BOM 会形成环形依赖: productId=" + targetProductId
                                + " 已在子件 " + startProductId + " 的 BOM 树中");
            }
            checkNoCycleFrom(childId, targetProductId, visited);
        }
    }
}
