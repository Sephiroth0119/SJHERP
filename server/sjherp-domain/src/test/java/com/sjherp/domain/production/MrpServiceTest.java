package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductRepository;
import com.sjherp.domain.catalog.UnitConversion;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * MrpService 单元测试——纯内存，无 Spring，无 DB。
 *
 * <p>所有端口用内部匿名/静态假实现（FakeXxx）代替，不使用 Mockito。
 * 验证 LLC 逐层净算算法的核心场景。
 */
class MrpServiceTest {

    // ================================================================ 假仓储

    /** 内存 BOM 仓储 */
    static class FakeBomRepository implements BillOfMaterialsRepository {
        private final Map<Long, BillOfMaterials> byId = new HashMap<>();
        private final Map<Long, BillOfMaterials> byProduct = new HashMap<>();
        private final AtomicLong idGen = new AtomicLong(1);

        void addBom(BillOfMaterials bom) {
            long id = idGen.getAndIncrement();
            bom.assignId(id);
            byId.put(id, bom);
            byProduct.put(bom.getProductId(), bom);
        }

        @Override
        public void save(BillOfMaterials bom) {
            if (bom.getId() == null) {
                long id = idGen.getAndIncrement();
                bom.assignId(id);
            }
            byId.put(bom.getId(), bom);
            byProduct.put(bom.getProductId(), bom);
        }

        @Override
        public Optional<BillOfMaterials> findById(long id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<BillOfMaterials> findByProductAndVersion(long productId, int version) {
            BillOfMaterials bom = byProduct.get(productId);
            if (bom != null && bom.getVersion() == version) {
                return Optional.of(bom);
            }
            return Optional.empty();
        }

        @Override
        public List<BillOfMaterials> findEnabledByProductId(long productId) {
            return findActiveByProductId(productId).map(List::of).orElseGet(List::of);
        }

        @Override
        public PageResult<BillOfMaterials> search(BillOfMaterialsQuery query) {
            return new PageResult<>(List.of(), 0, 1, 20);
        }

        @Override
        public Optional<BillOfMaterials> findActiveByProductId(long productId) {
            BillOfMaterials bom = byProduct.get(productId);
            if (bom != null && bom.getStatus() == ArchiveStatus.ENABLED) {
                return Optional.of(bom);
            }
            return Optional.empty();
        }

        @Override
        public List<Long> findChildProductIds(long productId) {
            BillOfMaterials bom = byProduct.get(productId);
            if (bom == null) return List.of();
            return bom.getLines().stream().map(BomLine::childProductId).toList();
        }

        @Override
        public boolean existsByProductAndVersion(long productId, int version) {
            return findByProductAndVersion(productId, version).isPresent();
        }
    }

    /** 内存需求计划仓储 */
    static class FakeDemandPlanRepository implements DemandPlanRepository {
        private final List<DemandPlan> plans = new ArrayList<>();
        private final AtomicLong idGen = new AtomicLong(1);

        void addPlan(DemandPlan plan) {
            plan.assignId(idGen.getAndIncrement());
            plans.add(plan);
        }

        @Override
        public void save(DemandPlan plan) {
            if (plan.getId() == null) {
                plan.assignId(idGen.getAndIncrement());
            }
            plans.removeIf(p -> p.getId().equals(plan.getId()));
            plans.add(plan);
        }

        @Override
        public Optional<DemandPlan> findByDocNo(String docNo) {
            return plans.stream().filter(p -> p.getDocNo().equals(docNo)).findFirst();
        }

        @Override
        public PageResult<DemandPlan> search(DemandPlanQuery query) {
            return new PageResult<>(List.of(), 0, 1, 20);
        }

        @Override
        public List<DemandPlan> findAllEnabled() {
            return plans.stream()
                    .filter(p -> p.getStatus() == ArchiveStatus.ENABLED)
                    .toList();
        }
    }

    /** 内存商品仓储 */
    static class FakeProductRepository implements ProductRepository {
        private final Map<Long, Product> byId = new HashMap<>();

        void add(Product product) {
            byId.put(product.getId(), product);
        }

        @Override
        public Optional<Product> findById(long id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public boolean existsByCode(String code) {
            return byId.values().stream().anyMatch(p -> p.getCode().equals(code));
        }

        @Override
        public void save(Product product) {
            byId.put(product.getId(), product);
        }

        @Override
        public PageResult<Product> search(com.sjherp.domain.catalog.ProductQuery query) {
            return new PageResult<>(List.of(), 0, 1, 20);
        }

        @Override
        public boolean existsByCategoryId(long categoryId) {
            return false;
        }

        @Override
        public boolean existsByUnitId(long unitId) {
            return false;
        }
    }

    /** 内存 MRP 运行仓储 */
    static class FakeMrpRunRepository implements MrpRunRepository {
        private final Map<String, MrpRun> byDocNo = new HashMap<>();
        private final AtomicLong idGen = new AtomicLong(1);

        @Override
        public void save(MrpRun run) {
            if (run.getId() == null) {
                run.assignId(idGen.getAndIncrement());
            }
            byDocNo.put(run.getDocNo(), run);
        }

        @Override
        public Optional<MrpRun> findByDocNo(String docNo) {
            return Optional.ofNullable(byDocNo.get(docNo));
        }

        @Override
        public PageResult<MrpRun> searchHistory(int page, int size) {
            return new PageResult<>(List.of(), 0, page, size);
        }
    }

    // ================================================================ 辅助工厂

    /** 单位 id：1=件（基本单位），2=箱，3=瓶 */
    static final long UNIT_PCS = 1L;
    static final long UNIT_BOX = 2L;
    static final long UNIT_BTL = 3L;

    /** 创建启用商品（无单位换算） */
    static Product enabledProduct(long id, String code, String name) {
        return Product.restore(id, code, name, null, null, UNIT_PCS, null,
                ArchiveStatus.ENABLED, null, List.of(),
                "system", Instant.now(), "system", Instant.now());
    }

    /** 创建启用商品，携带单位换算列表 */
    static Product enabledProductWithConversions(long id, String code, String name,
                                                  long baseUnitId,
                                                  List<UnitConversion> conversions) {
        return Product.restore(id, code, name, null, null, baseUnitId, null,
                ArchiveStatus.ENABLED, null, conversions,
                "system", Instant.now(), "system", Instant.now());
    }

    /** 创建启用 BOM（1 版本） */
    static BillOfMaterials enabledBom(long productId, List<BomLine> lines) {
        BillOfMaterials bom = new BillOfMaterials(productId, 1, null, lines, "system");
        // 默认 BOM 状态为 ENABLED，构造后直接用
        return bom;
    }

    /** 生成固定单号 */
    static final DocumentNumberGenerator COUNTER_GEN = new DocumentNumberGenerator() {
        private int seq = 0;
        @Override
        public String generate(DocumentNumberRule rule) {
            return rule.getPrefix() + "-TEST-" + (++seq);
        }
        @Override
        public String generate(DocumentNumberRule rule, java.time.YearMonth yearMonth) {
            return rule.getPrefix() + "-TEST-" + (++seq);
        }
    };

    // ================================================================ 字段

    private FakeBomRepository bomRepo;
    private FakeDemandPlanRepository planRepo;
    private FakeProductRepository productRepo;
    private FakeMrpRunRepository mrpRunRepo;
    private MrpService service;

    /** 默认测试用仓库 id */
    static final long WAREHOUSE_ID = 1L;

    /** 内存库存（可在每个测试中覆盖） */
    Map<Long, BigDecimal> inventory;

    MrpInventorySource inventorySource;
    MrpDemandSource demandSource;

    @BeforeEach
    void setUp() {
        bomRepo = new FakeBomRepository();
        planRepo = new FakeDemandPlanRepository();
        productRepo = new FakeProductRepository();
        mrpRunRepo = new FakeMrpRunRepository();
        inventory = new HashMap<>();
        // 默认库存：返回 0
        inventorySource = (warehouseId, productId) ->
                inventory.getOrDefault(productId, BigDecimal.ZERO);
        // 默认 SO 需求：空
        demandSource = Map::of;
        service = new MrpService(bomRepo, planRepo, productRepo,
                demandSource, inventorySource, mrpRunRepo, COUNTER_GEN);
    }

    // ================================================================ 测试案例

    /**
     * 场景 1：教科书三层案例
     * BOM: A → 2×B (件), B → 3×C (件)
     * 独立需求: A=100 件
     * 库存: A=10, B=20, C=50
     * 期望:
     *   A: PRODUCTION gross=100, net=90
     *   B: PRODUCTION gross=180 (90×2), net=160
     *   C: PURCHASE  gross=480 (160×3), net=430
     */
    @Test
    void 教科书三层_A_B_C_净算正确() {
        // 商品
        Product prodA = enabledProduct(1L, "A", "成品A");
        Product prodB = enabledProduct(2L, "B", "半成品B");
        Product prodC = enabledProduct(3L, "C", "原料C");
        productRepo.add(prodA);
        productRepo.add(prodB);
        productRepo.add(prodC);

        // BOM: A→2B, B→3C（均为件=UNIT_PCS，即基本单位，无需换算）
        BomLine aToBLine = new BomLine(2L, new BigDecimal("2"), BigDecimal.ZERO, UNIT_PCS);
        BomLine bToCLine = new BomLine(3L, new BigDecimal("3"), BigDecimal.ZERO, UNIT_PCS);
        bomRepo.addBom(enabledBom(1L, List.of(aToBLine)));
        bomRepo.addBom(enabledBom(2L, List.of(bToCLine)));

        // 库存
        inventory.put(1L, new BigDecimal("10"));
        inventory.put(2L, new BigDecimal("20"));
        inventory.put(3L, new BigDecimal("50"));

        // 需求计划: A=100
        DemandPlanLine lineA = new DemandPlanLine(1L, new BigDecimal("100"), UNIT_PCS, LocalDate.now());
        DemandPlan plan = new DemandPlan("DP-001", LocalDate.now(), null, List.of(lineA), "system");
        planRepo.addPlan(plan);

        MrpRunRequest req = new MrpRunRequest(WAREHOUSE_ID, true, false, null);
        MrpRun run = service.run(req, "tester");

        // 验证建议行（按商品 id 查找）
        Map<Long, MrpSuggestion> byProduct = toMap(run.getSuggestions());

        // A: PRODUCTION, net=90
        MrpSuggestion sA = byProduct.get(1L);
        assertThat(sA).isNotNull();
        assertEquals(SuggestionType.PRODUCTION, sA.type());
        assertThat(sA.grossRequirement()).isEqualByComparingTo("100");
        assertThat(sA.onHand()).isEqualByComparingTo("10");
        assertThat(sA.netRequirement()).isEqualByComparingTo("90");
        assertEquals(0, sA.level());

        // B: PRODUCTION, gross=180, net=160
        MrpSuggestion sB = byProduct.get(2L);
        assertThat(sB).isNotNull();
        assertEquals(SuggestionType.PRODUCTION, sB.type());
        assertThat(sB.grossRequirement()).isEqualByComparingTo("180");
        assertThat(sB.onHand()).isEqualByComparingTo("20");
        assertThat(sB.netRequirement()).isEqualByComparingTo("160");
        assertEquals(1, sB.level());

        // C: PURCHASE, gross=480, net=430
        MrpSuggestion sC = byProduct.get(3L);
        assertThat(sC).isNotNull();
        assertEquals(SuggestionType.PURCHASE, sC.type());
        assertThat(sC.grossRequirement()).isEqualByComparingTo("480");
        assertThat(sC.onHand()).isEqualByComparingTo("50");
        assertThat(sC.netRequirement()).isEqualByComparingTo("430");
        assertEquals(2, sC.level());
    }

    /**
     * 场景 2：带损耗
     * BOM: B → 3×C，损耗率 0.1（C 实际用量=net_B × 3 × (1+0.1) = 160×3×1.1 = 528）
     * 基于场景 1，C 库存=50
     * 期望 C net = 528 - 50 = 478
     */
    @Test
    void 带损耗_C净需求含损耗系数() {
        Product prodA = enabledProduct(1L, "A", "成品A");
        Product prodB = enabledProduct(2L, "B", "半成品B");
        Product prodC = enabledProduct(3L, "C", "原料C");
        productRepo.add(prodA);
        productRepo.add(prodB);
        productRepo.add(prodC);

        // A→2B（无损耗），B→3C（损耗0.1）
        BomLine aToBLine = new BomLine(2L, new BigDecimal("2"), BigDecimal.ZERO, UNIT_PCS);
        BomLine bToCLine = new BomLine(3L, new BigDecimal("3"), new BigDecimal("0.1"), UNIT_PCS);
        bomRepo.addBom(enabledBom(1L, List.of(aToBLine)));
        bomRepo.addBom(enabledBom(2L, List.of(bToCLine)));

        inventory.put(1L, new BigDecimal("10"));  // A库存=10，net_A=90
        inventory.put(2L, new BigDecimal("20"));  // B库存=20，gross_B=180，net_B=160
        inventory.put(3L, new BigDecimal("50"));  // C库存=50

        DemandPlanLine lineA = new DemandPlanLine(1L, new BigDecimal("100"), UNIT_PCS, LocalDate.now());
        DemandPlan plan = new DemandPlan("DP-001", LocalDate.now(), null, List.of(lineA), "system");
        planRepo.addPlan(plan);

        MrpRunRequest req = new MrpRunRequest(WAREHOUSE_ID, true, false, null);
        MrpRun run = service.run(req, "tester");

        Map<Long, MrpSuggestion> byProduct = toMap(run.getSuggestions());

        // C gross = 160×3×(1+0.1) = 528, net = 528-50 = 478
        MrpSuggestion sC = byProduct.get(3L);
        assertThat(sC).isNotNull();
        assertThat(sC.grossRequirement()).isEqualByComparingTo("528");
        assertThat(sC.netRequirement()).isEqualByComparingTo("478");
        assertEquals(SuggestionType.PURCHASE, sC.type());
    }

    /**
     * 场景 3：无 BOM 叶子 → PURCHASE 建议
     * 商品 P 无 BOM，独立需求=100，库存=10
     * 期望: PURCHASE，net=90
     */
    @Test
    void 无BOM叶子_生成采购建议() {
        Product prodP = enabledProduct(10L, "P", "原料P");
        productRepo.add(prodP);
        // 无 BOM

        inventory.put(10L, new BigDecimal("10"));

        DemandPlanLine line = new DemandPlanLine(10L, new BigDecimal("100"), UNIT_PCS, LocalDate.now());
        DemandPlan plan = new DemandPlan("DP-001", LocalDate.now(), null, List.of(line), "system");
        planRepo.addPlan(plan);

        MrpRunRequest req = new MrpRunRequest(WAREHOUSE_ID, true, false, null);
        MrpRun run = service.run(req, "tester");

        assertThat(run.getSuggestions()).hasSize(1);
        MrpSuggestion s = run.getSuggestions().get(0);
        assertEquals(SuggestionType.PURCHASE, s.type());
        assertThat(s.netRequirement()).isEqualByComparingTo("90");
    }

    /**
     * 场景 4：库存充足 → 净需求=0，无建议行
     * 独立需求=50，库存=50
     * 期望: 建议列表为空
     */
    @Test
    void 库存充足_净需求为零_无建议() {
        Product prodP = enabledProduct(11L, "P2", "商品P2");
        productRepo.add(prodP);

        inventory.put(11L, new BigDecimal("50"));

        DemandPlanLine line = new DemandPlanLine(11L, new BigDecimal("50"), UNIT_PCS, LocalDate.now());
        DemandPlan plan = new DemandPlan("DP-001", LocalDate.now(), null, List.of(line), "system");
        planRepo.addPlan(plan);

        MrpRunRequest req = new MrpRunRequest(WAREHOUSE_ID, true, false, null);
        MrpRun run = service.run(req, "tester");

        assertThat(run.getSuggestions()).isEmpty();
    }

    /**
     * 场景 5：空需求 → 返回空建议列表
     * 无需求计划，无 SO，includeForecast=true
     * 期望: 建议列表为空
     */
    @Test
    void 空需求_返回空建议列表() {
        // 无需求计划、无 SO

        MrpRunRequest req = new MrpRunRequest(WAREHOUSE_ID, true, false, null);
        MrpRun run = service.run(req, "tester");

        assertThat(run.getSuggestions()).isEmpty();
        assertThat(run.getDocNo()).isNotNull();
    }

    /**
     * 场景 6：includeSalesOrder=false → 仅使用预测，忽略 SO
     * SO 中有商品 X 需求=100，但 includeSalesOrder=false
     * 预测计划中只有商品 Y=30
     * 期望: 仅有 Y 的建议，无 X
     */
    @Test
    void includeSalesOrder为false_仅使用预测() {
        Product prodX = enabledProduct(20L, "X", "商品X");
        Product prodY = enabledProduct(21L, "Y", "商品Y");
        productRepo.add(prodX);
        productRepo.add(prodY);

        // SO 中有 X=100，但开关关闭
        MrpDemandSource soWithX = () -> Map.of(20L, new BigDecimal("100"));
        service = new MrpService(bomRepo, planRepo, productRepo,
                soWithX, inventorySource, mrpRunRepo, COUNTER_GEN);

        // 预测只有 Y=30
        DemandPlanLine lineY = new DemandPlanLine(21L, new BigDecimal("30"), UNIT_PCS, LocalDate.now());
        DemandPlan plan = new DemandPlan("DP-001", LocalDate.now(), null, List.of(lineY), "system");
        planRepo.addPlan(plan);

        // includeSalesOrder=false
        MrpRunRequest req = new MrpRunRequest(WAREHOUSE_ID, true, false, null);
        MrpRun run = service.run(req, "tester");

        Map<Long, MrpSuggestion> byProduct = toMap(run.getSuggestions());
        assertThat(byProduct).doesNotContainKey(20L);  // X 无建议
        assertThat(byProduct).containsKey(21L);         // Y 有建议
        assertThat(byProduct.get(21L).netRequirement()).isEqualByComparingTo("30");
    }

    /**
     * 场景 7：共用子件多父汇总
     * P1 净需求=50，BOM: P1→2C; P2 净需求=30，BOM: P2→3C
     * C 库存=50
     * 期望: C gross = 50×2 + 30×3 = 190, net = 140
     */
    @Test
    void 共用子件_多父毛需求汇总() {
        Product prodP1 = enabledProduct(30L, "P1", "成品P1");
        Product prodP2 = enabledProduct(31L, "P2", "成品P2");
        Product prodC  = enabledProduct(32L, "C",  "原料C");
        productRepo.add(prodP1);
        productRepo.add(prodP2);
        productRepo.add(prodC);

        // BOM: P1→2C, P2→3C（C 无 BOM，叶子）
        bomRepo.addBom(enabledBom(30L, List.of(new BomLine(32L, new BigDecimal("2"), BigDecimal.ZERO, UNIT_PCS))));
        bomRepo.addBom(enabledBom(31L, List.of(new BomLine(32L, new BigDecimal("3"), BigDecimal.ZERO, UNIT_PCS))));

        // P1 库存=0→net=50，P2 库存=0→net=30
        inventory.put(32L, new BigDecimal("50"));

        // 需求计划: P1=50, P2=30
        DemandPlanLine lineP1 = new DemandPlanLine(30L, new BigDecimal("50"), UNIT_PCS, LocalDate.now());
        DemandPlanLine lineP2 = new DemandPlanLine(31L, new BigDecimal("30"), UNIT_PCS, LocalDate.now());
        DemandPlan plan = new DemandPlan("DP-001", LocalDate.now(), null, List.of(lineP1, lineP2), "system");
        planRepo.addPlan(plan);

        MrpRunRequest req = new MrpRunRequest(WAREHOUSE_ID, true, false, null);
        MrpRun run = service.run(req, "tester");

        Map<Long, MrpSuggestion> byProduct = toMap(run.getSuggestions());

        // C gross = 50×2 + 30×3 = 190, net = 140
        MrpSuggestion sC = byProduct.get(32L);
        assertThat(sC).isNotNull();
        assertEquals(SuggestionType.PURCHASE, sC.type());
        assertThat(sC.grossRequirement()).isEqualByComparingTo("190");
        assertThat(sC.netRequirement()).isEqualByComparingTo("140");
    }

    /**
     * 场景 8：环形 BOM 防护
     * BOM: A→B, B→A（成环）
     * 期望: 抛出 BomCycleException
     */
    @Test
    void 环形BOM_抛BomCycleException() {
        Product prodA = enabledProduct(40L, "CycleA", "环A");
        Product prodB = enabledProduct(41L, "CycleB", "环B");
        productRepo.add(prodA);
        productRepo.add(prodB);

        // A→B, B→A 成环
        bomRepo.addBom(enabledBom(40L, List.of(new BomLine(41L, BigDecimal.ONE, BigDecimal.ZERO, UNIT_PCS))));
        bomRepo.addBom(enabledBom(41L, List.of(new BomLine(40L, BigDecimal.ONE, BigDecimal.ZERO, UNIT_PCS))));

        DemandPlanLine lineA = new DemandPlanLine(40L, new BigDecimal("10"), UNIT_PCS, LocalDate.now());
        DemandPlan plan = new DemandPlan("DP-001", LocalDate.now(), null, List.of(lineA), "system");
        planRepo.addPlan(plan);

        MrpRunRequest req = new MrpRunRequest(WAREHOUSE_ID, true, false, null);
        assertThrows(BomCycleException.class, () -> service.run(req, "tester"));
    }

    /**
     * 场景 9：单位换算
     * 商品 C 基本单位=瓶(3)，换算：1 箱(2) = 12 瓶
     * BOM: A → 2 箱 C（unitId=箱），net_A=10
     * 期望: C 毛需求 = 10 × 2 × 12 = 240 瓶（toBase 换算）
     * C 库存=0，net=240
     */
    @Test
    void 单位换算_BOM行箱换瓶() {
        Product prodA = enabledProduct(50L, "FG", "成品FG");
        // C 基本单位=瓶，带箱→瓶换算 rate=12
        UnitConversion boxToBottle = new UnitConversion(UNIT_BOX, new BigDecimal("12"));
        Product prodC = enabledProductWithConversions(51L, "COMP", "原料瓶装C",
                UNIT_BTL, List.of(boxToBottle));
        productRepo.add(prodA);
        productRepo.add(prodC);

        // BOM: A→2箱C（unitId=UNIT_BOX）
        BomLine line = new BomLine(51L, new BigDecimal("2"), BigDecimal.ZERO, UNIT_BOX);
        bomRepo.addBom(enabledBom(50L, List.of(line)));
        // C 无 BOM（叶子）

        // net_A = 10（库存=0），C 库存=0
        DemandPlanLine lineA = new DemandPlanLine(50L, new BigDecimal("10"), UNIT_PCS, LocalDate.now());
        DemandPlan plan = new DemandPlan("DP-001", LocalDate.now(), null, List.of(lineA), "system");
        planRepo.addPlan(plan);

        MrpRunRequest req = new MrpRunRequest(WAREHOUSE_ID, true, false, null);
        MrpRun run = service.run(req, "tester");

        Map<Long, MrpSuggestion> byProduct = toMap(run.getSuggestions());

        // C gross = 10 × 2 × 12 = 240，net = 240（库存0）
        MrpSuggestion sC = byProduct.get(51L);
        assertThat(sC).isNotNull();
        assertEquals(SuggestionType.PURCHASE, sC.type());
        assertThat(sC.grossRequirement()).isEqualByComparingTo("240");
        assertThat(sC.netRequirement()).isEqualByComparingTo("240");
        // baseUnitId 应为瓶(3)
        assertEquals(UNIT_BTL, sC.baseUnitId());
    }

    /**
     * 边界验证：两个需求来源都关闭 → IAE
     */
    @Test
    void 两个来源均关闭_抛IllegalArgumentException() {
        MrpRunRequest req = new MrpRunRequest(WAREHOUSE_ID, false, false, null);
        assertThrows(IllegalArgumentException.class, () -> service.run(req, "tester"));
    }

    /**
     * 边界验证：仓库 id=0 → IAE
     */
    @Test
    void 仓库id为零_抛IllegalArgumentException() {
        MrpRunRequest req = new MrpRunRequest(0L, true, false, null);
        assertThrows(IllegalArgumentException.class, () -> service.run(req, "tester"));
    }

    /**
     * 场景 10：菱形 BOM（共用子件跨层级，LLC 正确性核心，评审 P2）
     * BOM: A→1×B, A→1×C, B→2×D, C→3×D（D 经两条深度=2 的路径被引用）
     * 独立需求 A=10，各级库存 0
     * 期望: D 的 LLC=2，必须在最深层**一次性**净算（不重复减库存）：
     *   D 毛 = (10×1)×2[经B] + (10×1)×3[经C] = 50，net=50，level=2，**仅一条 D 建议行**
     */
    @Test
    void 菱形BOM_共用子件跨层级_LLC一次净算() {
        productRepo.add(enabledProduct(1L, "A", "成品A"));
        productRepo.add(enabledProduct(2L, "B", "半成品B"));
        productRepo.add(enabledProduct(3L, "C", "半成品C"));
        productRepo.add(enabledProduct(4L, "D", "原料D"));

        bomRepo.addBom(enabledBom(1L, List.of(
                new BomLine(2L, new BigDecimal("1"), BigDecimal.ZERO, UNIT_PCS),
                new BomLine(3L, new BigDecimal("1"), BigDecimal.ZERO, UNIT_PCS))));
        bomRepo.addBom(enabledBom(2L, List.of(
                new BomLine(4L, new BigDecimal("2"), BigDecimal.ZERO, UNIT_PCS))));
        bomRepo.addBom(enabledBom(3L, List.of(
                new BomLine(4L, new BigDecimal("3"), BigDecimal.ZERO, UNIT_PCS))));
        // D 无 BOM（叶子，采购）；全部库存 0

        DemandPlanLine lineA = new DemandPlanLine(1L, new BigDecimal("10"), UNIT_PCS, LocalDate.now());
        planRepo.addPlan(new DemandPlan("DP-001", LocalDate.now(), null, List.of(lineA), "system"));

        MrpRun run = service.run(new MrpRunRequest(WAREHOUSE_ID, true, false, null), "tester");

        // D 只应出现一条建议行（验证未在两条路径各净算一次 → 未重复减库存）
        List<MrpSuggestion> dRows = run.getSuggestions().stream()
                .filter(s -> s.productId() == 4L).toList();
        assertEquals(1, dRows.size(), "菱形共用子件 D 应在最深 LLC 层只净算一次（一条建议行）");
        MrpSuggestion sD = dRows.get(0);
        assertEquals(SuggestionType.PURCHASE, sD.type());
        assertThat(sD.grossRequirement()).isEqualByComparingTo("50");
        assertThat(sD.netRequirement()).isEqualByComparingTo("50");
        assertEquals(2, sD.level(), "D 的 LLC（最深层级）应为 2");
    }

    /**
     * 场景 10b：菱形 BOM 共用子件带库存，最深层一次性扣减
     * 同上 BOM，D 库存=20 → D 毛=50，net=30（一次扣减，非每条路径各扣）
     */
    @Test
    void 菱形BOM_共用子件带库存_最深层一次扣减() {
        productRepo.add(enabledProduct(1L, "A", "成品A"));
        productRepo.add(enabledProduct(2L, "B", "半成品B"));
        productRepo.add(enabledProduct(3L, "C", "半成品C"));
        productRepo.add(enabledProduct(4L, "D", "原料D"));
        bomRepo.addBom(enabledBom(1L, List.of(
                new BomLine(2L, new BigDecimal("1"), BigDecimal.ZERO, UNIT_PCS),
                new BomLine(3L, new BigDecimal("1"), BigDecimal.ZERO, UNIT_PCS))));
        bomRepo.addBom(enabledBom(2L, List.of(
                new BomLine(4L, new BigDecimal("2"), BigDecimal.ZERO, UNIT_PCS))));
        bomRepo.addBom(enabledBom(3L, List.of(
                new BomLine(4L, new BigDecimal("3"), BigDecimal.ZERO, UNIT_PCS))));
        inventory.put(4L, new BigDecimal("20"));

        DemandPlanLine lineA = new DemandPlanLine(1L, new BigDecimal("10"), UNIT_PCS, LocalDate.now());
        planRepo.addPlan(new DemandPlan("DP-001", LocalDate.now(), null, List.of(lineA), "system"));

        MrpRun run = service.run(new MrpRunRequest(WAREHOUSE_ID, true, false, null), "tester");

        MrpSuggestion sD = toMap(run.getSuggestions()).get(4L);
        assertThat(sD.grossRequirement()).isEqualByComparingTo("50");
        assertThat(sD.netRequirement()).isEqualByComparingTo("30"); // 50 − 20，仅扣一次
    }

    /**
     * 场景 11：单位换算缺失抛异常（单位归一红线兜底，评审 P2）
     * 子件 C 基本单位=瓶，无任何换算；BOM 行用箱（既非基本单位又无换算）→ 抛 IllegalArgumentException
     */
    @Test
    void 单位换算缺失_抛IllegalArgumentException() {
        productRepo.add(enabledProduct(50L, "FG", "成品FG"));
        // C 基本单位=瓶，无箱→瓶换算
        productRepo.add(enabledProductWithConversions(51L, "COMP", "原料瓶装C",
                UNIT_BTL, List.of()));
        // BOM 行用箱（UNIT_BOX），C 无该换算
        bomRepo.addBom(enabledBom(50L, List.of(
                new BomLine(51L, new BigDecimal("2"), BigDecimal.ZERO, UNIT_BOX))));

        DemandPlanLine lineA = new DemandPlanLine(50L, new BigDecimal("10"), UNIT_PCS, LocalDate.now());
        planRepo.addPlan(new DemandPlan("DP-001", LocalDate.now(), null, List.of(lineA), "system"));

        MrpRunRequest req = new MrpRunRequest(WAREHOUSE_ID, true, false, null);
        assertThrows(IllegalArgumentException.class, () -> service.run(req, "tester"));
    }

    // ================================================================ 辅助

    /** 将建议列表转换为以 productId 为键的 Map（便于断言） */
    private static Map<Long, MrpSuggestion> toMap(List<MrpSuggestion> suggestions) {
        Map<Long, MrpSuggestion> result = new HashMap<>();
        for (MrpSuggestion s : suggestions) {
            result.put(s.productId(), s);
        }
        return result;
    }
}
