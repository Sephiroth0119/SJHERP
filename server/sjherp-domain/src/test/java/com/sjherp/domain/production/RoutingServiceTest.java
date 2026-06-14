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
import com.sjherp.domain.catalog.ProductQuery;
import com.sjherp.domain.catalog.ProductRepository;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;

/**
 * 工艺路线领域服务单测（M5-T01）：创建/更新/启停/404/@Audited。
 *
 * <p>用内存替身仓储（domain 模块仅 JUnit5，沿用手写 Fake 的约定）。
 * 工艺路线无 BOM 的环检测逻辑，不含 cycle 相关测试。
 */
class RoutingServiceTest {

    private static final String OPERATOR = "tester";

    private static final long PRODUCT_A = 100L;
    private static final long PRODUCT_B = 200L;
    private static final long UNIT_ID   = 1L;

    private FakeRoutingRepository routingRepository;
    private FakeProductRepository productRepository;
    private RoutingService service;

    @BeforeEach
    void setUp() {
        routingRepository = new FakeRoutingRepository();
        productRepository = new FakeProductRepository();
        service = new RoutingService(routingRepository, productRepository);

        // 预置两个启用商品
        productRepository.put(enabledProduct(PRODUCT_A, "P-A", "产品A"));
        productRepository.put(enabledProduct(PRODUCT_B, "P-B", "产品B"));
    }

    // ================================================================ 辅助工厂

    /** 标准单工序路线命令 */
    private RoutingCommand cmd(long productId, int version) {
        return new RoutingCommand(productId, version, null,
                List.of(new RoutingOperationCommand(10, "切割", new BigDecimal("0.5"), null, null)));
    }

    private RoutingCommand cmd(long productId, int version, List<RoutingOperationCommand> ops) {
        return new RoutingCommand(productId, version, null, ops);
    }

    private RoutingOperationCommand opCmd(int seqNo, String name) {
        return new RoutingOperationCommand(seqNo, name, new BigDecimal("1.0"), null, null);
    }

    // ================================================================ 创建

    @Test
    void 创建工艺路线_默认ENABLED_版本可查() {
        Routing routing = service.create(cmd(PRODUCT_A, 1), OPERATOR);

        assertNotNull(routing.getId());
        assertEquals(PRODUCT_A, routing.getProductId());
        assertEquals(1, routing.getVersion());
        assertEquals(ArchiveStatus.ENABLED, routing.getStatus());
        assertEquals(OPERATOR, routing.getCreatedBy());
        assertNotNull(routing.getCreatedAt());
        assertEquals(1, routing.getOperations().size());
    }

    @Test
    void 创建_产品不存在_被拒绝() {
        long nonExistent = 9999L;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(cmd(nonExistent, 1), OPERATOR));
        assertTrue(e.getMessage().contains("不存在"), e.getMessage());
    }

    @Test
    void 创建_产品已停用_被拒绝() {
        productRepository.put(disabledProduct(PRODUCT_A, "P-A", "产品A"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(cmd(PRODUCT_A, 1), OPERATOR));
        assertTrue(e.getMessage().contains("已停用"), e.getMessage());
    }

    @Test
    void 创建_版本重复_被拒绝() {
        service.create(cmd(PRODUCT_A, 1), OPERATOR);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(cmd(PRODUCT_A, 1), OPERATOR));
        assertTrue(e.getMessage().contains("v1"), e.getMessage());
    }

    @Test
    void 工序序号重复_被拒绝() {
        // 两道工序相同 sequenceNo=10
        List<RoutingOperationCommand> ops = List.of(
                opCmd(10, "切割"),
                opCmd(10, "焊接"));  // 序号重复
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(cmd(PRODUCT_A, 1, ops), OPERATOR));
        assertTrue(e.getMessage().contains("重复"), e.getMessage());
    }

    // ================================================================ 版本切换

    @Test
    void 新建版本_自动停用旧ENABLED版本() {
        Routing v1 = service.create(cmd(PRODUCT_A, 1), OPERATOR);
        assertEquals(ArchiveStatus.ENABLED, v1.getStatus());

        Routing v2 = service.create(cmd(PRODUCT_A, 2), OPERATOR);
        assertEquals(ArchiveStatus.ENABLED, v2.getStatus());

        // v1 应被自动停用
        Routing v1Reload = service.get(v1.getId());
        assertEquals(ArchiveStatus.DISABLED, v1Reload.getStatus());
    }

    // ================================================================ 更新

    @Test
    void 更新工艺路线_工序整体替换() {
        Routing routing = service.create(cmd(PRODUCT_A, 1), OPERATOR);

        RoutingCommand updCmd = cmd(PRODUCT_A, 1, List.of(
                opCmd(10, "切割"),
                opCmd(20, "焊接")));
        Routing updated = service.update(routing.getId(), updCmd, OPERATOR);

        assertEquals(2, updated.getOperations().size());
    }

    // ================================================================ 启停规则

    @Test
    void 启用_停用_成功() {
        Routing routing = service.create(cmd(PRODUCT_A, 1), OPERATOR);
        long id = routing.getId();

        Routing disabled = service.disable(id, "boss");
        assertEquals(ArchiveStatus.DISABLED, disabled.getStatus());

        Routing enabled = service.enable(id, OPERATOR);
        assertEquals(ArchiveStatus.ENABLED, enabled.getStatus());
    }

    @Test
    void 重复启用_被拒绝() {
        Routing routing = service.create(cmd(PRODUCT_A, 1), OPERATOR);
        // 创建即 ENABLED，再次启用被拒
        assertThrows(IllegalArgumentException.class, () -> service.enable(routing.getId(), OPERATOR));
    }

    @Test
    void 重复停用_被拒绝() {
        Routing routing = service.create(cmd(PRODUCT_A, 1), OPERATOR);
        service.disable(routing.getId(), OPERATOR);
        assertThrows(IllegalArgumentException.class, () -> service.disable(routing.getId(), OPERATOR));
    }

    // ================================================================ 查询

    @Test
    void 查询不存在的工艺路线_抛404异常() {
        assertThrows(RoutingNotFoundException.class, () -> service.get(9999L));
    }

    @Test
    void 多产品多版本_互不干扰() {
        // 两个产品各建一条路线，互相不影响
        Routing ra = service.create(cmd(PRODUCT_A, 1), OPERATOR);
        Routing rb = service.create(cmd(PRODUCT_B, 1), OPERATOR);

        assertEquals(ArchiveStatus.ENABLED, ra.getStatus());
        assertEquals(ArchiveStatus.ENABLED, rb.getStatus());

        // A 建 v2 只停 A-v1，不影响 B-v1
        service.create(cmd(PRODUCT_A, 2), OPERATOR);
        assertEquals(ArchiveStatus.DISABLED, service.get(ra.getId()).getStatus());
        assertEquals(ArchiveStatus.ENABLED,  service.get(rb.getId()).getStatus());
    }

    // ================================================================ @Audited 标注校验

    @Test
    void 写方法均标注Audited切面() throws NoSuchMethodException {
        assertAudited("create", RoutingCommand.class, String.class);
        assertAudited("update", long.class, RoutingCommand.class, String.class);
        assertAudited("enable", long.class, String.class);
        assertAudited("disable", long.class, String.class);
    }

    private static void assertAudited(String name, Class<?>... params) throws NoSuchMethodException {
        var method = RoutingService.class.getMethod(name, params);
        var audited = method.getAnnotation(Audited.class);
        assertNotNull(audited, name + " 应标注 @Audited");
        assertEquals("ROUTING", audited.targetType(), name + " 的 @Audited targetType");
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

    /** 工艺路线内存仓储替身 */
    private static final class FakeRoutingRepository implements RoutingRepository {

        private final Map<Long, Routing> store = new LinkedHashMap<>();
        private final AtomicLong idGen = new AtomicLong();

        @Override
        public void save(Routing routing) {
            if (routing.getId() == null) {
                routing.assignId(idGen.incrementAndGet());
            }
            // 模拟 DB uk_routing_active 唯一索引：同产品至多一条 ENABLED（评审 P0 回归守门）。
            if (routing.getStatus() == ArchiveStatus.ENABLED) {
                boolean conflict = store.values().stream()
                        .anyMatch(r -> r.getProductId() == routing.getProductId()
                                && r.getStatus() == ArchiveStatus.ENABLED
                                && !r.getId().equals(routing.getId()));
                if (conflict) {
                    throw new IllegalStateException(
                            "违反 uk_routing_active：产品 " + routing.getProductId() + " 已存在启用版本");
                }
            }
            store.put(routing.getId(), routing);
        }

        @Override
        public Optional<Routing> findById(long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Optional<Routing> findByProductAndVersion(long productId, int version) {
            return store.values().stream()
                    .filter(r -> r.getProductId() == productId && r.getVersion() == version)
                    .findFirst();
        }

        @Override
        public List<Routing> findEnabledByProductId(long productId) {
            return store.values().stream()
                    .filter(r -> r.getProductId() == productId && r.getStatus() == ArchiveStatus.ENABLED)
                    .collect(Collectors.toList());
        }

        @Override
        public PageResult<Routing> search(RoutingQuery query) {
            List<Routing> all = new ArrayList<>(store.values());
            return new PageResult<>(all, all.size(), query.page(), query.size());
        }

        @Override
        public Optional<Routing> findActiveByProductId(long productId) {
            return findEnabledByProductId(productId).stream().findFirst();
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
