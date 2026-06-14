package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductRepository;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;

/**
 * 需求计划领域服务（唯一写入口，M5-T02）。
 *
 * <p>不可妥协原则：所有写操作经此服务，不可绕过；每个写方法标注 {@link Audited}。
 */
public class DemandPlanService {

    /** DP- 前缀规则 */
    private static final DocumentNumberRule DP_RULE = DocumentNumberRule.of("DP");

    private final DemandPlanRepository planRepository;
    private final ProductRepository productRepository;
    private final DocumentNumberGenerator numberGenerator;

    public DemandPlanService(DemandPlanRepository planRepository,
                             ProductRepository productRepository,
                             DocumentNumberGenerator numberGenerator) {
        this.planRepository = planRepository;
        this.productRepository = productRepository;
        this.numberGenerator = numberGenerator;
    }

    // ================================================================ 写操作（@Audited）

    /**
     * 创建需求计划。
     *
     * <p>校验：planDate 非空 → 至少一行 → 每行商品存在且启用 → 数量 &gt; 0 → 单位 id 非 0 → 行号唯一（商品+单位组合不重复）。
     */
    @Audited(action = "demand_plan.create", targetType = "DemandPlan")
    public DemandPlan create(DemandPlanCommand command, String operator) {
        Objects.requireNonNull(command, "命令不能为空");
        Objects.requireNonNull(command.planDate(), "planDate 不能为空");
        List<DemandPlanLine> lines = validateAndBuildLines(command.lines());

        String docNo = numberGenerator.generate(DP_RULE);
        DemandPlan plan = new DemandPlan(docNo, command.planDate(), command.remark(), lines, operator);
        planRepository.save(plan);
        return plan;
    }

    /**
     * 更新需求计划（行整体替换）。
     */
    @Audited(action = "demand_plan.update", targetType = "DemandPlan")
    public DemandPlan update(String docNo, DemandPlanCommand command, String operator) {
        DemandPlan plan = planRepository.findByDocNo(docNo)
                .orElseThrow(() -> DemandPlanNotFoundException.byDocNo(docNo));
        Objects.requireNonNull(command.planDate(), "planDate 不能为空");
        List<DemandPlanLine> lines = validateAndBuildLines(command.lines());
        plan.update(command.planDate(), command.remark(), lines, operator);
        planRepository.save(plan);
        return plan;
    }

    // ================================================================ 读操作

    /** 按文档号查询（不存在抛 404）。 */
    public DemandPlan get(String docNo) {
        return planRepository.findByDocNo(docNo)
                .orElseThrow(() -> DemandPlanNotFoundException.byDocNo(docNo));
    }

    /** 分页搜索。 */
    public PageResult<DemandPlan> search(DemandPlanQuery query) {
        return planRepository.search(query);
    }

    /** 所有启用需求计划（MRP 消费入口）。 */
    public List<DemandPlan> findAllEnabled() {
        return planRepository.findAllEnabled();
    }

    // ================================================================ 私有辅助

    private List<DemandPlanLine> validateAndBuildLines(List<DemandPlanLineCommand> cmds) {
        Objects.requireNonNull(cmds, "行列表不能为空");
        if (cmds.isEmpty()) {
            throw new IllegalArgumentException("需求计划至少需要一行");
        }
        List<DemandPlanLine> lines = new ArrayList<>();
        // 检查商品+单位组合唯一（同计划内同商品同单位不重复录入）
        Set<String> seen = new HashSet<>();
        for (DemandPlanLineCommand cmd : cmds) {
            // 商品存在且启用
            Product product = productRepository.findById(cmd.productId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "商品不存在: id=" + cmd.productId()));
            if (product.getStatus() != ArchiveStatus.ENABLED) {
                throw new IllegalArgumentException(
                        "商品已停用，不能加入需求计划: id=" + cmd.productId());
            }
            // 数量 > 0
            if (cmd.quantity() == null || cmd.quantity().signum() <= 0) {
                throw new IllegalArgumentException(
                        "需求数量必须大于 0: productId=" + cmd.productId());
            }
            // 精度 ≤ 6（用 stripTrailingZeros 与 BomLine/UnitConversion 同口径，避免尾零误判）
            if (cmd.quantity().stripTrailingZeros().scale() > 6) {
                throw new IllegalArgumentException(
                        "需求数量精度超过 6 位: productId=" + cmd.productId());
            }
            // 单位 id 非 0
            if (cmd.unitId() == 0) {
                throw new IllegalArgumentException(
                        "单位 id 不能为 0: productId=" + cmd.productId());
            }
            // 组合唯一
            String key = cmd.productId() + ":" + cmd.unitId();
            if (!seen.add(key)) {
                throw new IllegalArgumentException(
                        "同一计划内商品+单位组合重复: productId=" + cmd.productId()
                                + ", unitId=" + cmd.unitId());
            }
            lines.add(new DemandPlanLine(cmd.productId(), cmd.quantity(), cmd.unitId(), cmd.dueDate()));
        }
        return lines;
    }
}
