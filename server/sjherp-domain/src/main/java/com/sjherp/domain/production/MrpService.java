package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductRepository;
import com.sjherp.domain.catalog.UnitConversion;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;

/**
 * MRP 逐层净算核心领域服务（M5-T02）。
 *
 * <p><b>算法：Low-Level-Code (LLC) 逐层 netting</b>
 * <ol>
 *   <li>汇总毛需求：销售订单实时聚合 + 启用需求计划预测（按开关选择），均换算为子件基本单位。</li>
 *   <li>计算 LLC：每个商品在所有 BOM 树中出现的最深层级（保证父件先于子件处理）。</li>
 *   <li>按层级升序处理：gross = 该商品当前毛需求；net = max(gross - onHand, 0)；
 *       net &gt; 0 且有 active BOM → 生产建议 + 用 <b>net</b>（非 gross）展开子件毛需求。</li>
 *   <li>无 active BOM → 采购建议（叶子，停止展开）。</li>
 * </ol>
 *
 * <p><b>与 explode 的本质区别</b>：explode 把毛需求当净需求向下传；MRP 必须父件先净算得到净需求，
 * 再用净需求×行用量×(1+损耗) 作子件毛需求，防止重复减库存。
 *
 * <p>单位换算：BOM 行用量按行 unitId 换算到子件基本单位；库存 onHand 已是基本单位；
 * 换算缺失抛 {@link IllegalArgumentException}（不静默）。
 *
 * <p>精度：乘法中间不舍入，最终建议量在 {@link #RESULT_SCALE} 位 HALF_UP。
 */
public class MrpService {

    /** 最终建议量精度（6 位 HALF_UP，与 DECIMAL(18,6) 对齐） */
    private static final int RESULT_SCALE = 6;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** MRP- 前缀规则 */
    private static final DocumentNumberRule MRP_RULE = DocumentNumberRule.of("MRP");

    private final BillOfMaterialsRepository bomRepository;
    private final DemandPlanRepository demandPlanRepository;
    private final ProductRepository productRepository;
    private final MrpDemandSource demandSource;
    private final MrpInventorySource inventorySource;
    private final MrpRunRepository mrpRunRepository;
    private final DocumentNumberGenerator numberGenerator;

    public MrpService(BillOfMaterialsRepository bomRepository,
                      DemandPlanRepository demandPlanRepository,
                      ProductRepository productRepository,
                      MrpDemandSource demandSource,
                      MrpInventorySource inventorySource,
                      MrpRunRepository mrpRunRepository,
                      DocumentNumberGenerator numberGenerator) {
        this.bomRepository = bomRepository;
        this.demandPlanRepository = demandPlanRepository;
        this.productRepository = productRepository;
        this.demandSource = demandSource;
        this.inventorySource = inventorySource;
        this.mrpRunRepository = mrpRunRepository;
        this.numberGenerator = numberGenerator;
    }

    // ================================================================ 公开入口

    /**
     * 触发 MRP 运行（全重算，regenerative），持久化并返回运行结果。
     *
     * @param request  运行参数（仓库、来源开关、备注）
     * @param operator 操作人
     * @return 持久化后的 MRP 运行聚合
     */
    @Audited(action = "mrp.run", targetType = "MRP_RUN")
    public MrpRun run(MrpRunRequest request, String operator) {
        Objects.requireNonNull(request, "运行请求不能为空");
        if (request.warehouseId() <= 0) {
            throw new IllegalArgumentException("仓库 id 必须大于 0");
        }
        if (!request.includeForecast() && !request.includeSalesOrder()) {
            throw new IllegalArgumentException("至少选择一个需求来源（预测或销售订单）");
        }

        // 1. 汇总顶层毛需求（基本单位）
        Map<Long, BigDecimal> grossDemand = new HashMap<>();

        if (request.includeSalesOrder()) {
            Map<Long, BigDecimal> soDemand = demandSource.openSalesOrderDemand();
            soDemand.forEach((pid, qty) -> grossDemand.merge(pid, qty, BigDecimal::add));
        }

        if (request.includeForecast()) {
            for (DemandPlan plan : demandPlanRepository.findAllEnabled()) {
                for (DemandPlanLine line : plan.getLines()) {
                    Product product = productRepository.findById(line.productId())
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "需求计划引用的商品不存在: id=" + line.productId()));
                    BigDecimal baseQty = toBase(line.productId(), line.quantity(),
                            line.unitId(), product);
                    grossDemand.merge(line.productId(), baseQty, BigDecimal::add);
                }
            }
        }

        if (grossDemand.isEmpty()) {
            // 无需求，返回空结果
            String docNo = numberGenerator.generate(MRP_RULE);
            MrpRun emptyRun = new MrpRun(docNo, Instant.now(), request.warehouseId(),
                    request.includeForecast(), request.includeSalesOrder(),
                    request.remark(), operator, List.of());
            mrpRunRepository.save(emptyRun);
            return emptyRun;
        }

        // 2. 计算所有涉及商品的 Low-Level-Code（LLC）
        //    LLC(p) = 该商品在所有 BOM 树中出现的最深层级（0 = 独立需求根）
        Map<Long, Integer> llcMap = new HashMap<>();
        // 将初始需求商品设为 llc=0（若未被子件引用则最终 llc=0）
        for (long pid : grossDemand.keySet()) {
            llcMap.putIfAbsent(pid, 0);
        }
        // 递归计算 LLC（无环保护：复用 visitedPath）
        for (long pid : new HashSet<>(grossDemand.keySet())) {
            computeLlc(pid, 0, llcMap, new HashSet<>());
        }

        // 3. 按 LLC 升序逐层净算
        //    netByProduct = 当前未净算毛需求（随子件展开动态累加）
        Map<Long, BigDecimal> netByProduct = new HashMap<>(grossDemand);

        // 最大层级（动态更新，子件展开后可能增大）
        int maxLevel = llcMap.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        List<MrpSuggestion> suggestions = new ArrayList<>();

        for (int level = 0; level <= maxLevel; level++) {
            final int currentLevel = level;
            // 该层有毛需求的商品
            List<Long> productIds = netByProduct.entrySet().stream()
                    .filter(e -> {
                        Integer llc = llcMap.get(e.getKey());
                        return llc != null && llc == currentLevel && e.getValue().signum() > 0;
                    })
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            for (long productId : productIds) {
                BigDecimal gross = netByProduct.get(productId);
                if (gross == null || gross.signum() <= 0) {
                    continue;
                }

                BigDecimal onHand = inventorySource.onHand(request.warehouseId(), productId);
                BigDecimal net = gross.subtract(onHand);
                if (net.signum() <= 0) {
                    // 库存充足，净需求 = 0，无需展开
                    continue;
                }
                net = net.setScale(RESULT_SCALE, ROUNDING);

                Product product = productRepository.findById(productId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "MRP 处理商品不存在: id=" + productId));

                Optional<BillOfMaterials> activeBom = bomRepository.findActiveByProductId(productId);

                if (activeBom.isPresent()) {
                    // 有 active BOM → 生产建议，继续展开子件（用 net 非 gross）
                    suggestions.add(new MrpSuggestion(
                            SuggestionType.PRODUCTION, productId, level,
                            gross.setScale(RESULT_SCALE, ROUNDING),
                            onHand.setScale(RESULT_SCALE, ROUNDING),
                            net,
                            product.getBaseUnitId()));

                    // 用 NET 展开子件毛需求
                    BillOfMaterials bom = activeBom.get();
                    Set<Long> visitedPath = new HashSet<>();
                    visitedPath.add(productId);
                    for (BomLine line : bom.getLines()) {
                        long childId = line.childProductId();
                        if (visitedPath.contains(childId)) {
                            throw new BomCycleException(
                                    "MRP 展开发现环形依赖: productId=" + childId
                                            + " 已在访问路径中（历史脏数据或跨版本成环）");
                        }
                        Product childProduct = productRepository.findById(childId)
                                .orElseThrow(() -> new IllegalArgumentException(
                                        "BOM 子件商品不存在: id=" + childId));

                        // 子件毛需求 = net × 行用量 × (1 + 损耗率)，换算到子件基本单位
                        BigDecimal childGrossInLineUnit = net
                                .multiply(line.quantity())
                                .multiply(BigDecimal.ONE.add(line.scrapRate()));
                        BigDecimal childGrossBase = toBase(childId, childGrossInLineUnit,
                                line.unitId(), childProduct);

                        netByProduct.merge(childId, childGrossBase, BigDecimal::add);

                        // 确保子件出现在 llcMap（至少 currentLevel+1）
                        llcMap.merge(childId, currentLevel + 1, Math::max);
                    }
                } else {
                    // 无 active BOM → 采购建议（叶子，停止展开）
                    suggestions.add(new MrpSuggestion(
                            SuggestionType.PURCHASE, productId, level,
                            gross.setScale(RESULT_SCALE, ROUNDING),
                            onHand.setScale(RESULT_SCALE, ROUNDING),
                            net,
                            product.getBaseUnitId()));
                }
            }

            // 子件展开后 llcMap 可能增大，动态更新最大层级
            if (level == maxLevel) {
                int newMax = llcMap.values().stream().mapToInt(Integer::intValue).max().orElse(0);
                if (newMax > maxLevel) {
                    maxLevel = newMax;
                }
            }
        }

        // 4. 持久化
        String docNo = numberGenerator.generate(MRP_RULE);
        MrpRun mrpRun = new MrpRun(docNo, Instant.now(), request.warehouseId(),
                request.includeForecast(), request.includeSalesOrder(),
                request.remark(), operator, suggestions);
        mrpRunRepository.save(mrpRun);
        return mrpRun;
    }

    /** 按文档号查询（不存在抛 404）。 */
    public MrpRun get(String docNo) {
        return mrpRunRepository.findByDocNo(docNo)
                .orElseThrow(() -> MrpRunNotFoundException.byDocNo(docNo));
    }

    // ================================================================ 私有辅助

    /**
     * 递归计算 LLC：从 productId 出发沿 active BOM 向下，
     * 记录每个商品在整棵树中出现的最深层级。
     *
     * @param productId   当前商品 id
     * @param depth       当前深度（从 0 开始）
     * @param llcMap      LLC 累计映射（最大深度持续更新）
     * @param visitedPath 当前递归路径（环检测）
     */
    private void computeLlc(long productId, int depth, Map<Long, Integer> llcMap,
                             Set<Long> visitedPath) {
        // 更新当前商品的 LLC（取已知最大深度）
        llcMap.merge(productId, depth, Math::max);

        Optional<BillOfMaterials> bom = bomRepository.findActiveByProductId(productId);
        if (bom.isEmpty()) {
            return; // 叶子，无子件
        }

        if (visitedPath.contains(productId)) {
            throw new BomCycleException(
                    "LLC 计算发现环形依赖: productId=" + productId);
        }

        Set<Long> childPath = new HashSet<>(visitedPath);
        childPath.add(productId);

        for (BomLine line : bom.get().getLines()) {
            computeLlc(line.childProductId(), depth + 1, llcMap, childPath);
        }
    }

    /**
     * 将数量从 BOM 行单位换算到子件基本单位。
     *
     * <p>若 unitId 已是子件基本单位（rate = 1），直接返回；
     * 否则查子件 Product.unitConversions 找对应换算关系；
     * 找不到则抛 {@link IllegalArgumentException}（不静默）。
     *
     * @param productId   子件商品 id（用于错误信息）
     * @param quantity    行单位数量
     * @param unitId      行单位 id
     * @param product     子件 Product 聚合（已加载，避免重复查库）
     * @return 基本单位数量（不舍入，保持中间精度）
     */
    private static BigDecimal toBase(long productId, BigDecimal quantity,
                                     long unitId, Product product) {
        if (unitId == product.getBaseUnitId()) {
            // 已是基本单位，rate = 1，不做换算
            return quantity;
        }
        // 查 unitConversions 列表
        for (UnitConversion uc : product.getUnitConversions()) {
            if (uc.unitId() == unitId) {
                return uc.toBaseQuantity(quantity);
            }
        }
        throw new IllegalArgumentException(
                "商品 " + productId + " 不存在单位 id=" + unitId + " 的换算关系");
    }
}
