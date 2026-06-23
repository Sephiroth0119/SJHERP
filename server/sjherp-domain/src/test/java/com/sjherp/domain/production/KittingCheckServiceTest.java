package com.sjherp.domain.production;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.event.DomainEventPublisher;

/**
 * KittingCheckService 单元测试（M5-T04）——纯内存，无 Spring，无 DB，无 Mockito。
 *
 * <p>验证：无 BOM 时齐套为 true、库存充足时 kitted=true、
 * 库存不足时 kitted=false + 缺料量计算、含损耗率的毛需求计算。
 */
class KittingCheckServiceTest {

    // ================================================================ Fake 仓储与端口

    static class FakeBillOfMaterialsRepository implements BillOfMaterialsRepository {
        /** productId → active BOM */
        final Map<Long, BillOfMaterials> activeByProduct = new HashMap<>();

        void setActive(long productId, BillOfMaterials bom) {
            activeByProduct.put(productId, bom);
        }

        @Override
        public void save(BillOfMaterials bom) {
            // 测试不需要写
        }

        @Override
        public Optional<BillOfMaterials> findById(long id) {
            return Optional.empty();
        }

        @Override
        public Optional<BillOfMaterials> findByProductAndVersion(long productId, int version) {
            return Optional.empty();
        }

        @Override
        public List<BillOfMaterials> findEnabledByProductId(long productId) {
            return List.of();
        }

        @Override
        public PageResult<BillOfMaterials> search(BillOfMaterialsQuery query) {
            return new PageResult<>(List.of(), 0, 1, 20);
        }

        @Override
        public Optional<BillOfMaterials> findActiveByProductId(long productId) {
            return Optional.ofNullable(activeByProduct.get(productId));
        }

        @Override
        public List<Long> findChildProductIds(long parentProductId) {
            return List.of();
        }

        @Override
        public boolean existsByProductAndVersion(long productId, int version) {
            return false;
        }
    }

    /** Fake 库存可用量端口：productId → onHand 数量。 */
    static class FakeInventoryAvailabilityPort implements InventoryAvailabilityPort {
        final Map<Long, BigDecimal> onHandByProduct = new HashMap<>();

        void setOnHand(long productId, BigDecimal qty) {
            onHandByProduct.put(productId, qty);
        }

        @Override
        public BigDecimal onHand(long warehouseId, long productId) {
            return onHandByProduct.getOrDefault(productId, BigDecimal.ZERO);
        }
    }

    // ================================================================ 被测服务

    private FakeBillOfMaterialsRepository bomRepo;
    private FakeInventoryAvailabilityPort availabilityPort;
    private KittingCheckService service;
    private DomainEventPublisher eventPublisher = event -> {};

    /** 测试用仓库 id */
    private static final long WAREHOUSE_ID = 1L;

    @BeforeEach
    void setUp() {
        bomRepo = new FakeBillOfMaterialsRepository();
        availabilityPort = new FakeInventoryAvailabilityPort();
        service = new KittingCheckService(bomRepo, availabilityPort);
    }

    // ---------------------------------------------------------------- 辅助方法

    /** 构造开工状态的工单（KittingCheck 只需 docNo 和 plannedQty） */
    private WorkOrder buildExecutingWorkOrder(String docNo, long productId, BigDecimal plannedQty) {
        WorkOrder wo = WorkOrder.create(docNo, productId, plannedQty, 1L,
                null, null, null, null, null, null, "op");
        wo.assignId(1L);
        wo.registerEventPublisher(eventPublisher);
        wo.release("op");
        wo.start("op");
        return wo;
    }

    /** 构造并注册一个 active BOM（单子件，无损耗） */
    private BillOfMaterials buildBomWithLine(long parentProductId, long childProductId,
                                              BigDecimal qty, BigDecimal scrapRate) {
        BomLine line = new BomLine(childProductId, qty, scrapRate, 1L);
        BillOfMaterials bom = new BillOfMaterials(parentProductId, 1, null, List.of(line), "op");
        bomRepo.setActive(parentProductId, bom);
        return bom;
    }

    // ---------------------------------------------------------------- 测试用例

    @Test
    void check_无activeBOM_齐套为true_行为空() {
        // 工单对应商品无 BOM——视为无物料需求，直接 kitted=true
        WorkOrder wo = buildExecutingWorkOrder("WO-001", 100L, new BigDecimal("10"));

        KittingCheck result = service.check(wo, WAREHOUSE_ID);

        assertThat(result.kitted()).isTrue();
        assertThat(result.lines()).isEmpty();
        assertThat(result.workOrderDocNo()).isEqualTo("WO-001");
    }

    @Test
    void check_库存充足_kittedTrue_shortage为0() {
        // 父件 100，子件 201：净用量 2，无损耗；工单 5 件 → 毛需求 = 10
        WorkOrder wo = buildExecutingWorkOrder("WO-001", 100L, new BigDecimal("5"));
        buildBomWithLine(100L, 201L, new BigDecimal("2"), BigDecimal.ZERO);
        // 库存 15，需求 10，充足
        availabilityPort.setOnHand(201L, new BigDecimal("15"));

        KittingCheck result = service.check(wo, WAREHOUSE_ID);

        assertThat(result.kitted()).isTrue();
        assertThat(result.lines()).hasSize(1);
        KittingCheckLine line = result.lines().get(0);
        assertThat(line.shortage()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(line.required()).isEqualByComparingTo("10.000000");
        assertThat(line.available()).isEqualByComparingTo("15");
    }

    @Test
    void check_库存不足_kittedFalse_shortage计算正确() {
        // 父件 100，子件 201：净用量 3，无损耗；工单 4 件 → 毛需求 = 12
        // 库存只有 8，缺料 12 - 8 = 4
        WorkOrder wo = buildExecutingWorkOrder("WO-001", 100L, new BigDecimal("4"));
        buildBomWithLine(100L, 201L, new BigDecimal("3"), BigDecimal.ZERO);
        availabilityPort.setOnHand(201L, new BigDecimal("8"));

        KittingCheck result = service.check(wo, WAREHOUSE_ID);

        assertThat(result.kitted()).isFalse();
        KittingCheckLine line = result.lines().get(0);
        assertThat(line.shortage()).isEqualByComparingTo("4.000000");
    }

    @Test
    void check_含损耗率_毛需求按加成法计算() {
        // 净用量 2，损耗率 0.1，加成法毛需求 = netQty × (1 + 0.1) = 5 × 2 × 1.1 = 11
        WorkOrder wo = buildExecutingWorkOrder("WO-001", 100L, new BigDecimal("5"));
        buildBomWithLine(100L, 201L, new BigDecimal("2"), new BigDecimal("0.1"));
        // 库存 20，够用（11 ≤ 20）
        availabilityPort.setOnHand(201L, new BigDecimal("20"));

        KittingCheck result = service.check(wo, WAREHOUSE_ID);

        assertThat(result.kitted()).isTrue();
        KittingCheckLine line = result.lines().get(0);
        // 毛需求 = 5 × 2 × 1.1 = 11
        assertThat(line.required()).isEqualByComparingTo("11.000000");
        assertThat(line.shortage()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void check_多子件_任一不足则kittedFalse() {
        // 父件 100：子件 201（用量 1，无损耗）+ 子件 202（用量 2，无损耗）
        // 工单 5 件 → 201 需求 5，202 需求 10
        WorkOrder wo = buildExecutingWorkOrder("WO-001", 100L, new BigDecimal("5"));
        BomLine line1 = new BomLine(201L, new BigDecimal("1"), BigDecimal.ZERO, 1L);
        BomLine line2 = new BomLine(202L, new BigDecimal("2"), BigDecimal.ZERO, 1L);
        BillOfMaterials bom = new BillOfMaterials(100L, 1, null, List.of(line1, line2), "op");
        bomRepo.setActive(100L, bom);
        // 201 库存充足（6 ≥ 5），202 库存不足（7 < 10）
        availabilityPort.setOnHand(201L, new BigDecimal("6"));
        availabilityPort.setOnHand(202L, new BigDecimal("7"));

        KittingCheck result = service.check(wo, WAREHOUSE_ID);

        assertThat(result.kitted()).isFalse();
        assertThat(result.lines()).hasSize(2);
        // 202 缺料 10 - 7 = 3
        KittingCheckLine checkedLine202 = result.lines().stream()
                .filter(l -> l.productId() == 202L)
                .findFirst().orElseThrow();
        assertThat(checkedLine202.shortage()).isEqualByComparingTo("3.000000");
        // 201 不缺料
        KittingCheckLine checkedLine201 = result.lines().stream()
                .filter(l -> l.productId() == 201L)
                .findFirst().orElseThrow();
        assertThat(checkedLine201.shortage()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
