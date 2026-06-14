package com.sjherp.domain.production;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductRepository;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;

/**
 * 工艺路线领域服务（唯一写入口，M5-T01）。
 *
 * <p>不可妥协原则 3：每个写方法标注 {@link Audited}，审计切面自动记录。
 */
public class RoutingService {

    private final RoutingRepository routingRepository;
    private final ProductRepository productRepository;

    public RoutingService(RoutingRepository routingRepository,
                          ProductRepository productRepository) {
        this.routingRepository = routingRepository;
        this.productRepository = productRepository;
    }

    // ================================================================ 写操作（@Audited）

    /**
     * 创建工艺路线（默认 ENABLED，自动停用同产品其他启用版本）。
     */
    @Audited(action = "routing.create", targetType = "ROUTING")
    public Routing create(RoutingCommand command, String operator) {
        Objects.requireNonNull(command, "命令不能为空");
        validateProduct(command.productId());
        if (routingRepository.existsByProductAndVersion(command.productId(), command.version())) {
            throw new IllegalArgumentException(
                    "该产品已存在 v" + command.version() + " 的工艺路线，请选择其他版本号: productId="
                            + command.productId());
        }

        List<RoutingOperation> operations = buildOperations(command.operations());

        // 关键顺序：先停用同产品其他 ENABLED 版本，再插入新的 ENABLED 版本——
        // 否则 INSERT 即撞 DB active_flag 唯一索引抛约束异常（评审 P0）。
        // 新工艺路线尚未持久化（无 id），传 0L 不匹配任何真实 id → 停用该产品当前全部 ENABLED 版本。
        disableOtherEnabledVersions(command.productId(), 0L, operator);

        Routing routing = new Routing(command.productId(), command.version(),
                command.remark(), operations, operator);
        routingRepository.save(routing);
        return routing;
    }

    /** 更新工艺路线内容（工序整体替换） */
    @Audited(action = "routing.update", targetType = "ROUTING")
    public Routing update(long id, RoutingCommand command, String operator) {
        Routing routing = routingRepository.findById(id)
                .orElseThrow(() -> RoutingNotFoundException.byId(id));

        List<RoutingOperation> operations = buildOperations(command.operations());
        routing.update(command.remark(), operations, operator);
        routingRepository.save(routing);
        return routing;
    }

    /** 启用工艺路线（同事务先停用同产品其他 ENABLED 版本） */
    @Audited(action = "routing.enable", targetType = "ROUTING")
    public Routing enable(long id, String operator) {
        Routing routing = routingRepository.findById(id)
                .orElseThrow(() -> RoutingNotFoundException.byId(id));
        disableOtherEnabledVersions(routing.getProductId(), id, operator);
        routing.enable(operator);
        routingRepository.save(routing);
        return routing;
    }

    /** 停用工艺路线 */
    @Audited(action = "routing.disable", targetType = "ROUTING")
    public Routing disable(long id, String operator) {
        Routing routing = routingRepository.findById(id)
                .orElseThrow(() -> RoutingNotFoundException.byId(id));
        routing.disable(operator);
        routingRepository.save(routing);
        return routing;
    }

    // ================================================================ 读操作

    /** 按 id 查询（不存在抛 404） */
    public Routing get(long id) {
        return routingRepository.findById(id)
                .orElseThrow(() -> RoutingNotFoundException.byId(id));
    }

    /** 分页搜索 */
    public PageResult<Routing> search(RoutingQuery query) {
        return routingRepository.search(query);
    }

    // ================================================================ 私有辅助

    /** 停用同 productId 下其他 ENABLED 版本（排除当前 id） */
    private void disableOtherEnabledVersions(long productId, long excludeId, String operator) {
        List<Routing> others = routingRepository.findEnabledByProductId(productId);
        for (Routing other : others) {
            if (!other.getId().equals(excludeId)) {
                other.disable(operator);
                routingRepository.save(other);
            }
        }
    }

    /** 校验产品存在且已启用 */
    private void validateProduct(long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("产品不存在: id=" + productId));
        if (product.getStatus() != ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("产品已停用，不能建立工艺路线: id=" + productId);
        }
    }

    /** 构建工序值对象列表 */
    private List<RoutingOperation> buildOperations(List<RoutingOperationCommand> cmds) {
        Objects.requireNonNull(cmds, "工序列表不能为空");
        if (cmds.isEmpty()) {
            throw new IllegalArgumentException("工艺路线至少需要一道工序");
        }
        List<RoutingOperation> ops = new ArrayList<>();
        for (RoutingOperationCommand cmd : cmds) {
            ops.add(new RoutingOperation(
                    cmd.sequenceNo(),
                    cmd.operationName(),
                    cmd.standardHours(),
                    cmd.workCenter(),
                    cmd.costRate()));
        }
        return ops;
    }
}
