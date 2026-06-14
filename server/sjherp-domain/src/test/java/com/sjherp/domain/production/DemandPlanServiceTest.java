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
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * DemandPlanService 单元测试——纯内存，无 Spring，无 DB。
 *
 * <p>所有端口用内部匿名/静态假实现代替，不使用 Mockito。
 * 测试校验逻辑、单号生成、更新整体替换及未找到异常。
 */
class DemandPlanServiceTest {

    // ================================================================ 假仓储

    /** 内存需求计划仓储 */
    static class FakeDemandPlanRepository implements DemandPlanRepository {
        final Map<String, DemandPlan> byDocNo = new HashMap<>();
        final AtomicLong idGen = new AtomicLong(1);

        @Override
        public void save(DemandPlan plan) {
            if (plan.getId() == null) {
                plan.assignId(idGen.getAndIncrement());
            }
            byDocNo.put(plan.getDocNo(), plan);
        }

        @Override
        public Optional<DemandPlan> findByDocNo(String docNo) {
            return Optional.ofNullable(byDocNo.get(docNo));
        }

        @Override
        public PageResult<DemandPlan> search(DemandPlanQuery query) {
            return new PageResult<>(new ArrayList<>(byDocNo.values()), byDocNo.size(), query.page(), query.size());
        }

        @Override
        public List<DemandPlan> findAllEnabled() {
            return byDocNo.values().stream()
                    .filter(p -> p.getStatus() == ArchiveStatus.ENABLED)
                    .toList();
        }
    }

    /** 内存商品仓储 */
    static class FakeProductRepository implements ProductRepository {
        final Map<Long, Product> byId = new HashMap<>();

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

    // ================================================================ 辅助工厂

    static final long UNIT_PCS = 1L;
    static final long UNIT_BOX = 2L;

    static Product enabledProduct(long id, String code, String name) {
        return Product.restore(id, code, name, null, null, UNIT_PCS, null,
                ArchiveStatus.ENABLED, null, List.of(),
                "system", Instant.now(), "system", Instant.now());
    }

    static Product disabledProduct(long id, String code, String name) {
        return Product.restore(id, code, name, null, null, UNIT_PCS, null,
                ArchiveStatus.DISABLED, null, List.of(),
                "system", Instant.now(), "system", Instant.now());
    }

    /** 生成固定格式单号 */
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

    private FakeDemandPlanRepository planRepo;
    private FakeProductRepository productRepo;
    private DemandPlanService service;

    @BeforeEach
    void setUp() {
        planRepo = new FakeDemandPlanRepository();
        productRepo = new FakeProductRepository();
        service = new DemandPlanService(planRepo, productRepo, COUNTER_GEN);
    }

    // ================================================================ 测试案例

    /**
     * 场景 1：正常创建
     * 命令含 2 行，校验通过 → 返回含 docNo 的 DemandPlan
     */
    @Test
    void 正常创建_返回含docNo的计划() {
        productRepo.add(enabledProduct(1L, "PA", "商品A"));
        productRepo.add(enabledProduct(2L, "PB", "商品B"));

        DemandPlanLineCommand lineA = new DemandPlanLineCommand(
                1L, new BigDecimal("100"), UNIT_PCS, LocalDate.now().plusDays(7));
        DemandPlanLineCommand lineB = new DemandPlanLineCommand(
                2L, new BigDecimal("50"), UNIT_PCS, LocalDate.now().plusDays(14));
        DemandPlanCommand cmd = new DemandPlanCommand(
                LocalDate.now(), "季度预测", List.of(lineA, lineB));

        DemandPlan plan = service.create(cmd, "operator");

        assertNotNull(plan.getDocNo());
        assertThat(plan.getDocNo()).startsWith("DP");
        assertThat(plan.getLines()).hasSize(2);
        // 验证落库
        assertThat(planRepo.byDocNo).containsKey(plan.getDocNo());
    }

    /**
     * 场景 2：商品不存在 → IAE
     * 行引用不存在的商品 id=999
     */
    @Test
    void 商品不存在_抛IllegalArgumentException() {
        // productRepo 中没有 id=999 的商品
        DemandPlanLineCommand line = new DemandPlanLineCommand(
                999L, new BigDecimal("10"), UNIT_PCS, null);
        DemandPlanCommand cmd = new DemandPlanCommand(LocalDate.now(), null, List.of(line));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cmd, "operator"));
        assertThat(ex.getMessage()).contains("999");
    }

    /**
     * 场景 3：商品已停用 → IAE
     */
    @Test
    void 商品已停用_抛IllegalArgumentException() {
        productRepo.add(disabledProduct(5L, "DISABLED", "停用商品"));

        DemandPlanLineCommand line = new DemandPlanLineCommand(
                5L, new BigDecimal("10"), UNIT_PCS, null);
        DemandPlanCommand cmd = new DemandPlanCommand(LocalDate.now(), null, List.of(line));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cmd, "operator"));
        assertThat(ex.getMessage()).contains("停用");
    }

    /**
     * 场景 4：数量 ≤ 0 → IAE
     */
    @Test
    void 数量为零_抛IllegalArgumentException() {
        productRepo.add(enabledProduct(10L, "Q0", "商品Q"));

        DemandPlanLineCommand line = new DemandPlanLineCommand(
                10L, BigDecimal.ZERO, UNIT_PCS, null);
        DemandPlanCommand cmd = new DemandPlanCommand(LocalDate.now(), null, List.of(line));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cmd, "operator"));
        assertThat(ex.getMessage()).contains("大于 0");
    }

    /**
     * 场景 4b：数量为负 → IAE
     */
    @Test
    void 数量为负_抛IllegalArgumentException() {
        productRepo.add(enabledProduct(10L, "Q-", "商品Q负"));

        DemandPlanLineCommand line = new DemandPlanLineCommand(
                10L, new BigDecimal("-1"), UNIT_PCS, null);
        DemandPlanCommand cmd = new DemandPlanCommand(LocalDate.now(), null, List.of(line));

        assertThrows(IllegalArgumentException.class, () -> service.create(cmd, "operator"));
    }

    /**
     * 场景 5：行内商品+单位组合重复 → IAE
     * 两行均为 productId=1, unitId=UNIT_PCS
     */
    @Test
    void 行内商品单位组合重复_抛IllegalArgumentException() {
        productRepo.add(enabledProduct(1L, "PA", "商品A"));

        DemandPlanLineCommand line1 = new DemandPlanLineCommand(
                1L, new BigDecimal("100"), UNIT_PCS, null);
        DemandPlanLineCommand line2 = new DemandPlanLineCommand(
                1L, new BigDecimal("50"), UNIT_PCS, null); // 相同 product+unit
        DemandPlanCommand cmd = new DemandPlanCommand(LocalDate.now(), null, List.of(line1, line2));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(cmd, "operator"));
        assertThat(ex.getMessage()).contains("重复");
    }

    /**
     * 场景 5b：同商品不同单位组合 → 合法（不报重复）
     */
    @Test
    void 同商品不同单位_合法无异常() {
        productRepo.add(enabledProduct(1L, "PA", "商品A"));

        DemandPlanLineCommand line1 = new DemandPlanLineCommand(
                1L, new BigDecimal("100"), UNIT_PCS, null);
        DemandPlanLineCommand line2 = new DemandPlanLineCommand(
                1L, new BigDecimal("5"), UNIT_BOX, null); // 不同单位
        DemandPlanCommand cmd = new DemandPlanCommand(LocalDate.now(), null, List.of(line1, line2));

        DemandPlan plan = service.create(cmd, "operator");
        assertThat(plan.getLines()).hasSize(2);
    }

    /**
     * 场景 6：更新整体替换
     * 先创建一个 2 行的计划，再更新为 1 行
     * 期望: 行数变为 1，数量更新
     */
    @Test
    void 更新_行整体替换() {
        productRepo.add(enabledProduct(1L, "PA", "商品A"));
        productRepo.add(enabledProduct(2L, "PB", "商品B"));

        // 创建 2 行
        DemandPlanLineCommand lineA = new DemandPlanLineCommand(
                1L, new BigDecimal("100"), UNIT_PCS, null);
        DemandPlanLineCommand lineB = new DemandPlanLineCommand(
                2L, new BigDecimal("50"), UNIT_PCS, null);
        DemandPlanCommand createCmd = new DemandPlanCommand(
                LocalDate.now(), "原备注", List.of(lineA, lineB));
        DemandPlan created = service.create(createCmd, "operator");
        String docNo = created.getDocNo();

        // 更新：只保留 A，数量改 200
        DemandPlanLineCommand newLineA = new DemandPlanLineCommand(
                1L, new BigDecimal("200"), UNIT_PCS, null);
        DemandPlanCommand updateCmd = new DemandPlanCommand(
                LocalDate.now().plusDays(1), "新备注", List.of(newLineA));
        DemandPlan updated = service.update(docNo, updateCmd, "operator");

        assertThat(updated.getLines()).hasSize(1);
        assertThat(updated.getLines().get(0).quantity()).isEqualByComparingTo("200");
        assertEquals("新备注", updated.getRemark());
    }

    /**
     * 场景 7：找不到计划 → DemandPlanNotFoundException
     * 按不存在的 docNo 更新
     */
    @Test
    void 计划不存在_抛DemandPlanNotFoundException() {
        productRepo.add(enabledProduct(1L, "PA", "商品A"));

        DemandPlanLineCommand line = new DemandPlanLineCommand(
                1L, new BigDecimal("10"), UNIT_PCS, null);
        DemandPlanCommand cmd = new DemandPlanCommand(LocalDate.now(), null, List.of(line));

        assertThrows(DemandPlanNotFoundException.class,
                () -> service.update("DP-NOT-EXIST", cmd, "operator"));
    }

    /**
     * 边界：空行列表 → IAE
     */
    @Test
    void 空行列表_抛IllegalArgumentException() {
        DemandPlanCommand cmd = new DemandPlanCommand(LocalDate.now(), null, List.of());
        assertThrows(IllegalArgumentException.class, () -> service.create(cmd, "operator"));
    }

    /**
     * 边界：planDate 为 null → NPE（Objects.requireNonNull）
     */
    @Test
    void planDate为null_抛异常() {
        productRepo.add(enabledProduct(1L, "PA", "商品A"));
        DemandPlanLineCommand line = new DemandPlanLineCommand(
                1L, new BigDecimal("10"), UNIT_PCS, null);
        DemandPlanCommand cmd = new DemandPlanCommand(null, null, List.of(line));

        assertThrows(NullPointerException.class, () -> service.create(cmd, "operator"));
    }
}
