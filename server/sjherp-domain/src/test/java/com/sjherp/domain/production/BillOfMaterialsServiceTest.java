package com.sjherp.domain.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductRepository;
import com.sjherp.domain.catalog.ProductQuery;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;

/**
 * BOM 领域服务单测（M5-T01）：创建/更新/启停/环检测/explode/@Audited。
 *
 * <p>用内存替身仓储（domain 模块仅 JUnit5，沿用手写 Fake 的约定）。
 */
class BillOfMaterialsServiceTest {

    private static final String OPERATOR = "tester";

    /** 产品 id 常量，方便测试用 */
    private static final long PRODUCT_A = 100L;
    private static final long PRODUCT_B = 200L;
    private static final long PRODUCT_C = 300L;
    private static final long UNIT_ID   = 1L;

    private FakeBillOfMaterialsRepository bomRepository;
    private FakeProductRepository productRepository;
    private BillOfMaterialsService service;

    @BeforeEach
    void setUp() {
        bomRepository = new FakeBillOfMaterialsRepository();
        productRepository = new FakeProductRepository();
        service = new BillOfMaterialsService(bomRepository, productRepository);

        // 预置三个启用商品
        productRepository.put(enabledProduct(PRODUCT_A, "P-A", "产品A"));
        productRepository.put(enabledProduct(PRODUCT_B, "P-B", "产品B"));
        productRepository.put(enabledProduct(PRODUCT_C, "P-C", "产品C"));
    }

    // ================================================================ 辅助工厂

    /** 构建 BOM 命令：一行子件，无损耗 */
    private BillOfMaterialsCommand cmd(long productId, int version, long childId) {
        return new BillOfMaterialsCommand(productId, version, null,
                List.of(new BomLineCommand(childId, BigDecimal.ONE, BigDecimal.ZERO, UNIT_ID)));
    }

    /** 多行子件的 BOM 命令 */
    private BillOfMaterialsCommand cmd(long productId, int version, List<BomLineCommand> lines) {
        return new BillOfMaterialsCommand(productId, version, null, lines);
    }

    private BomLineCommand lineCmd(long childId, BigDecimal qty, BigDecimal scrapRate) {
        return new BomLineCommand(childId, qty, scrapRate, UNIT_ID);
    }

    private BomLineCommand lineCmd(long childId) {
        return new BomLineCommand(childId, BigDecimal.ONE, BigDecimal.ZERO, UNIT_ID);
    }

    // ================================================================ 创建

    @Test
    void 创建BOM_默认ENABLED_版本可查() {
        // A→B
        BillOfMaterials bom = service.create(cmd(PRODUCT_A, 1, PRODUCT_B), OPERATOR);

        assertNotNull(bom.getId());
        assertEquals(PRODUCT_A, bom.getProductId());
        assertEquals(1, bom.getVersion());
        assertEquals(ArchiveStatus.ENABLED, bom.getStatus());
        assertEquals(OPERATOR, bom.getCreatedBy());
        assertNotNull(bom.getCreatedAt());
        assertEquals(1, bom.getLines().size());
        assertEquals(PRODUCT_B, bom.getLines().get(0).childProductId());
    }

    @Test
    void 创建BOM_父件不存在_被拒绝() {
        long nonExistent = 9999L;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(cmd(nonExistent, 1, PRODUCT_B), OPERATOR));
        assertTrue(e.getMessage().contains("不存在"), e.getMessage());
    }

    @Test
    void 创建BOM_父件已停用_被拒绝() {
        productRepository.put(disabledProduct(PRODUCT_A, "P-A", "产品A"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(cmd(PRODUCT_A, 1, PRODUCT_B), OPERATOR));
        assertTrue(e.getMessage().contains("已停用"), e.getMessage());
    }

    @Test
    void 创建BOM_版本重复_被拒绝() {
        service.create(cmd(PRODUCT_A, 1, PRODUCT_B), OPERATOR);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(cmd(PRODUCT_A, 1, PRODUCT_C), OPERATOR));
        assertTrue(e.getMessage().contains("v1"), e.getMessage());
    }

    @Test
    void 子件不存在_被拒绝() {
        long nonExistent = 9998L;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(cmd(PRODUCT_A, 1, nonExistent), OPERATOR));
        assertTrue(e.getMessage().contains("不存在"), e.getMessage());
    }

    @Test
    void 子件已停用_被拒绝() {
        productRepository.put(disabledProduct(PRODUCT_B, "P-B", "产品B"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(cmd(PRODUCT_A, 1, PRODUCT_B), OPERATOR));
        assertTrue(e.getMessage().contains("已停用"), e.getMessage());
    }

    @Test
    void 自引用父件_被拒绝() {
        // 子件 == 父件 → 聚合根构造器也拒绝，但此处经服务层的环检测提前快速路径拒绝
        assertThrows(BomCycleException.class,
                () -> service.create(cmd(PRODUCT_A, 1, PRODUCT_A), OPERATOR));
    }

    @Test
    void BOM行子件重复_被拒绝() {
        // 两行相同子件 id
        List<BomLineCommand> lines = List.of(lineCmd(PRODUCT_B), lineCmd(PRODUCT_B));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(cmd(PRODUCT_A, 1, lines), OPERATOR));
        assertTrue(e.getMessage().contains("重复"), e.getMessage());
    }

    // ================================================================ 环检测

    @Test
    void 环检测_直接自引用_被拒绝() {
        // A→A
        assertThrows(BomCycleException.class,
                () -> service.create(cmd(PRODUCT_A, 1, PRODUCT_A), OPERATOR));
    }

    @Test
    void 环检测_两节点互引用_被拒绝() {
        // 先建 A→B，再建 B→A：B 的子件 A 的 BOM 树包含 B，形成环
        service.create(cmd(PRODUCT_A, 1, PRODUCT_B), OPERATOR);

        BomCycleException e = assertThrows(BomCycleException.class,
                () -> service.create(cmd(PRODUCT_B, 1, PRODUCT_A), OPERATOR));
        assertNotNull(e.getMessage());
    }

    @Test
    void 环检测_三节点环_被拒绝() {
        // A→B, B→C, 再建 C→A 形成 A→B→C→A 的环
        service.create(cmd(PRODUCT_A, 1, PRODUCT_B), OPERATOR);
        service.create(cmd(PRODUCT_B, 1, PRODUCT_C), OPERATOR);

        BomCycleException e = assertThrows(BomCycleException.class,
                () -> service.create(cmd(PRODUCT_C, 1, PRODUCT_A), OPERATOR));
        assertNotNull(e.getMessage());
    }

    @Test
    void 环检测_多子件之一成环_被拒绝() {
        // 先建 A→B，再建 B→[C, A]：第二行子件 A 会形成环
        service.create(cmd(PRODUCT_A, 1, PRODUCT_B), OPERATOR);

        List<BomLineCommand> lines = List.of(lineCmd(PRODUCT_C), lineCmd(PRODUCT_A));
        assertThrows(BomCycleException.class,
                () -> service.create(cmd(PRODUCT_B, 1, lines), OPERATOR));
    }

    // ================================================================ 版本切换

    @Test
    void 创建新版本_自动停用旧ENABLED版本() {
        // v1 创建时默认 ENABLED
        BillOfMaterials v1 = service.create(cmd(PRODUCT_A, 1, PRODUCT_B), OPERATOR);
        assertEquals(ArchiveStatus.ENABLED, v1.getStatus());

        // 再建 v2 → v1 应被自动停用
        BillOfMaterials v2 = service.create(cmd(PRODUCT_A, 2, PRODUCT_C), OPERATOR);

        assertEquals(ArchiveStatus.ENABLED, v2.getStatus());
        // v1 在仓储中已被停用
        BillOfMaterials v1Reload = service.get(v1.getId());
        assertEquals(ArchiveStatus.DISABLED, v1Reload.getStatus());
    }

    // ================================================================ 启停规则

    @Test
    void 启用_停用_成功() {
        BillOfMaterials bom = service.create(cmd(PRODUCT_A, 1, PRODUCT_B), OPERATOR);
        long id = bom.getId();

        BillOfMaterials disabled = service.disable(id, "boss");
        assertEquals(ArchiveStatus.DISABLED, disabled.getStatus());

        BillOfMaterials enabled = service.enable(id, OPERATOR);
        assertEquals(ArchiveStatus.ENABLED, enabled.getStatus());
    }

    @Test
    void 重复启用_被拒绝() {
        BillOfMaterials bom = service.create(cmd(PRODUCT_A, 1, PRODUCT_B), OPERATOR);
        // 创建即 ENABLED，再次启用被拒
        assertThrows(IllegalArgumentException.class, () -> service.enable(bom.getId(), OPERATOR));
    }

    @Test
    void 重复停用_被拒绝() {
        BillOfMaterials bom = service.create(cmd(PRODUCT_A, 1, PRODUCT_B), OPERATOR);
        service.disable(bom.getId(), OPERATOR);
        assertThrows(IllegalArgumentException.class, () -> service.disable(bom.getId(), OPERATOR));
    }

    @Test
    void 查询不存在的BOM_抛404异常() {
        assertThrows(BillOfMaterialsNotFoundException.class, () -> service.get(9999L));
    }

    // ================================================================ explode

    @Test
    void explode_叶节点_无子件返回空列表() {
        // A 没有 active BOM → 展开应返回空节点列表
        BomExplosion result = service.explode(PRODUCT_A, BigDecimal.ONE);

        assertEquals(PRODUCT_A, result.rootProductId());
        assertEquals(0, new BigDecimal("1").compareTo(result.rootQuantity()));
        assertTrue(result.nodes().isEmpty(), "无 active BOM 时节点列表应为空");
    }

    @Test
    void explode_单层_正确展开() {
        // A → [B qty=2, C qty=3]
        List<BomLineCommand> lines = List.of(
                lineCmd(PRODUCT_B, new BigDecimal("2"), BigDecimal.ZERO),
                lineCmd(PRODUCT_C, new BigDecimal("3"), BigDecimal.ZERO));
        service.create(cmd(PRODUCT_A, 1, lines), OPERATOR);

        BomExplosion result = service.explode(PRODUCT_A, BigDecimal.ONE);

        assertEquals(2, result.nodes().size());
        BomExplosionNode nodeB = result.nodes().stream()
                .filter(n -> n.productId() == PRODUCT_B).findFirst().orElseThrow();
        BomExplosionNode nodeC = result.nodes().stream()
                .filter(n -> n.productId() == PRODUCT_C).findFirst().orElseThrow();

        assertEquals(0, new BigDecimal("2").compareTo(nodeB.quantity()),
                "B 毛需求应为 2（1 × 2 × (1+0)）");
        assertEquals(0, new BigDecimal("3").compareTo(nodeC.quantity()),
                "C 毛需求应为 3");
        assertEquals(1, nodeB.level(), "B 在第 1 层");
        assertTrue(nodeB.children().isEmpty(), "B 为叶节点无子件");
    }

    @Test
    void explode_多层_递归展开() {
        // A→B, B→C 两层
        service.create(cmd(PRODUCT_A, 1, PRODUCT_B), OPERATOR);
        service.create(cmd(PRODUCT_B, 1, PRODUCT_C), OPERATOR);

        BomExplosion result = service.explode(PRODUCT_A, BigDecimal.ONE);

        assertEquals(1, result.nodes().size());
        BomExplosionNode nodeB = result.nodes().get(0);
        assertEquals(PRODUCT_B, nodeB.productId());
        assertEquals(1, nodeB.level());
        assertEquals(1, nodeB.children().size());

        BomExplosionNode nodeC = nodeB.children().get(0);
        assertEquals(PRODUCT_C, nodeC.productId());
        assertEquals(2, nodeC.level());
        assertTrue(nodeC.children().isEmpty(), "C 是叶节点");
    }

    @Test
    void explode_含损耗率_毛需求计算正确() {
        // A→B，损耗率 0.1，父件净需求 10 → 子件毛需求 10 × 1.1 = 11
        List<BomLineCommand> lines = List.of(
                lineCmd(PRODUCT_B, BigDecimal.ONE, new BigDecimal("0.1")));
        service.create(cmd(PRODUCT_A, 1, lines), OPERATOR);

        BomExplosion result = service.explode(PRODUCT_A, new BigDecimal("10"));

        assertEquals(1, result.nodes().size());
        BigDecimal expected = new BigDecimal("11.0");
        assertEquals(0, expected.compareTo(result.nodes().get(0).quantity()),
                "损耗率 0.1，父件 10 → 子件毛需求应为 11");
    }

    @Test
    void explode_脏数据环形_爆BomCycleException() {
        // 正常建立 A→B（已经过环检测）
        service.create(cmd(PRODUCT_A, 1, PRODUCT_B), OPERATOR);

        // 直接向仓储注入 B→A 的 BOM（绕过服务层，模拟历史脏数据/直接写库）
        BillOfMaterials dirtyBom = new BillOfMaterials(
                PRODUCT_B, 1, "脏数据",
                List.of(new BomLine(PRODUCT_A, BigDecimal.ONE, BigDecimal.ZERO, UNIT_ID)),
                "system");
        bomRepository.injectDirty(dirtyBom);

        // explode 应发现环并抛 BomCycleException
        assertThrows(BomCycleException.class,
                () -> service.explode(PRODUCT_A, BigDecimal.ONE));
    }

    // ================================================================ @Audited 标注校验

    @Test
    void 写方法均标注Audited切面() throws NoSuchMethodException {
        assertAudited("create", BillOfMaterialsCommand.class, String.class);
        assertAudited("update", long.class, BillOfMaterialsCommand.class, String.class);
        assertAudited("enable", long.class, String.class);
        assertAudited("disable", long.class, String.class);
    }

    private static void assertAudited(String name, Class<?>... params) throws NoSuchMethodException {
        var method = BillOfMaterialsService.class.getMethod(name, params);
        var audited = method.getAnnotation(Audited.class);
        assertNotNull(audited, name + " 应标注 @Audited");
        assertEquals("BOM", audited.targetType(), name + " 的 @Audited targetType");
    }

    // ================================================================ 测试桩工厂

    private static Product enabledProduct(long id, String code, String name) {
        return Product.restore(id, code, name, null, null, UNIT_ID, null,
                ArchiveStatus.ENABLED, null, List.of(),
                "system", Instant.now(), "system", Instant.now());
    }

    private static Product disabledProduct(long id, String code, String name) {
        return Product.restore(id, code, name, null, null, UNIT_ID, null,
                ArchiveStatus.DISABLED, null, List.of(),
                "system", Instant.now(), "system", Instant.now());
    }

    // ================================================================ 内存替身仓储

    /** BOM 内存仓储替身 */
    private static final class FakeBillOfMaterialsRepository implements BillOfMaterialsRepository {

        private final Map<Long, BillOfMaterials> store = new LinkedHashMap<>();
        private final AtomicLong idGen = new AtomicLong();

        @Override
        public void save(BillOfMaterials bom) {
            if (bom.getId() == null) {
                bom.assignId(idGen.incrementAndGet());
            }
            // 模拟 DB uk_bom_active 唯一索引：同产品至多一条 ENABLED（评审 P0 回归守门）。
            // 若先插 ENABLED 再停旧版本（错误顺序），此处即抛，等价真库约束冲突。
            if (bom.getStatus() == ArchiveStatus.ENABLED) {
                boolean conflict = store.values().stream()
                        .anyMatch(b -> b.getProductId() == bom.getProductId()
                                && b.getStatus() == ArchiveStatus.ENABLED
                                && !b.getId().equals(bom.getId()));
                if (conflict) {
                    throw new IllegalStateException(
                            "违反 uk_bom_active：产品 " + bom.getProductId() + " 已存在启用版本");
                }
            }
            store.put(bom.getId(), bom);
        }

        /**
         * 绕过服务层直接注入脏数据（仅测试使用，模拟历史脏数据或直接写库场景）。
         * 注入时覆盖同 productId 下的 active BOM，使 explode 能拾取到脏数据。
         */
        void injectDirty(BillOfMaterials bom) {
            bom.assignId(idGen.incrementAndGet());
            store.put(bom.getId(), bom);
        }

        @Override
        public Optional<BillOfMaterials> findById(long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Optional<BillOfMaterials> findByProductAndVersion(long productId, int version) {
            return store.values().stream()
                    .filter(b -> b.getProductId() == productId && b.getVersion() == version)
                    .findFirst();
        }

        @Override
        public List<BillOfMaterials> findEnabledByProductId(long productId) {
            return store.values().stream()
                    .filter(b -> b.getProductId() == productId && b.getStatus() == ArchiveStatus.ENABLED)
                    .collect(Collectors.toList());
        }

        @Override
        public PageResult<BillOfMaterials> search(BillOfMaterialsQuery query) {
            List<BillOfMaterials> all = new ArrayList<>(store.values());
            return new PageResult<>(all, all.size(), query.page(), query.size());
        }

        @Override
        public Optional<BillOfMaterials> findActiveByProductId(long productId) {
            return findEnabledByProductId(productId).stream().findFirst();
        }

        @Override
        public List<Long> findChildProductIds(long parentProductId) {
            return findActiveByProductId(parentProductId)
                    .map(bom -> bom.getLines().stream()
                            .map(BomLine::childProductId)
                            .collect(Collectors.toList()))
                    .orElse(List.of());
        }

        @Override
        public boolean existsByProductAndVersion(long productId, int version) {
            return findByProductAndVersion(productId, version).isPresent();
        }
    }

    /** 商品内存仓储替身（仅 findById 被服务层使用） */
    private static final class FakeProductRepository implements ProductRepository {

        private final Map<Long, Product> store = new LinkedHashMap<>();

        void put(Product product) {
            store.put(product.getId(), product);
        }

        @Override
        public void save(Product product) {
            store.put(product.getId(), product);
        }

        @Override
        public Optional<Product> findById(long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public boolean existsByCode(String code) {
            return store.values().stream().anyMatch(p -> p.getCode().equals(code));
        }

        @Override
        public PageResult<Product> search(ProductQuery query) {
            List<Product> all = new ArrayList<>(store.values());
            return new PageResult<>(all, all.size(), query.page(), query.size());
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
}
