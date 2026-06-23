package com.sjherp.domain.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;

/**
 * MaterialReturnService 单元测试（M5-T04）——纯内存，无 Spring，无 DB，无 Mockito。
 *
 * <p>验证：退料单创建校验（原领料单必须 COMPLETED）、审核流转、过账按原单价退回
 * （issuedCost/quantity 算 unitCost）、returnedCost 回填、不存在时抛 404 异常。
 */
class MaterialReturnServiceTest {

    // ================================================================ Fake 仓储与端口

    static class FakeMaterialReturnRepository implements MaterialReturnRepository {
        final Map<String, MaterialReturn> store = new HashMap<>();

        @Override
        public void save(MaterialReturn mr) {
            store.put(mr.getDocNo(), mr);
        }

        @Override
        public Optional<MaterialReturn> findByDocNo(String docNo) {
            return Optional.ofNullable(store.get(docNo));
        }

        @Override
        public PageResult<MaterialReturn> search(MaterialReturnQuery query) {
            List<MaterialReturn> all = List.copyOf(store.values());
            return new PageResult<>(all, all.size(), query.page(), query.size());
        }
    }

    static class FakeMaterialIssueRepository implements MaterialIssueRepository {
        final Map<String, MaterialIssue> store = new HashMap<>();

        void add(MaterialIssue mi) {
            store.put(mi.getDocNo(), mi);
        }

        @Override
        public void save(MaterialIssue mi) {
            store.put(mi.getDocNo(), mi);
        }

        @Override
        public Optional<MaterialIssue> findByDocNo(String docNo) {
            return Optional.ofNullable(store.get(docNo));
        }

        @Override
        public PageResult<MaterialIssue> search(MaterialIssueQuery query) {
            List<MaterialIssue> all = List.copyOf(store.values());
            return new PageResult<>(all, all.size(), query.page(), query.size());
        }
    }

    /**
     * Fake 库存过账端口：入库命令返回 totalCost = quantity × unitCost（InboundCommand 参数）。
     * 校验 unitCost 是否按原领料单成本计算。
     */
    static class FakeInventoryPostingPort implements InventoryPostingPort {
        final List<InboundCommand> captured = new ArrayList<>();

        @Override
        public List<StockMovementResult> execute(List<StockMovementCommand> batch, String operator) {
            List<StockMovementResult> results = new ArrayList<>();
            for (StockMovementCommand cmd : batch) {
                if (cmd instanceof InboundCommand ic) {
                    captured.add(ic);
                    // 入库 totalCost 为正
                    BigDecimal totalCost = ic.quantity().multiply(ic.unitCost());
                    results.add(new StockMovementResult(
                            1L, ic.warehouseId(), ic.productId(),
                            InventoryTxnType.PRODUCTION_RETURN, ic.quantity(),
                            ic.unitCost(), totalCost,
                            BigDecimal.ZERO, BigDecimal.ZERO,
                            ic.srcDocType(), ic.srcDocNo(), ic.srcLineNo(), ic.idempotencyKey()));
                }
            }
            return results;
        }
    }

    // ================================================================ 被测服务

    private FakeMaterialReturnRepository mrRepo;
    private FakeMaterialIssueRepository miRepo;
    private FakeInventoryPostingPort inventoryPort;
    private MaterialReturnService service;
    private DomainEventPublisher eventPublisher = event -> {};

    @BeforeEach
    void setUp() {
        mrRepo = new FakeMaterialReturnRepository();
        miRepo = new FakeMaterialIssueRepository();
        inventoryPort = new FakeInventoryPostingPort();
        service = new MaterialReturnService(mrRepo, miRepo, inventoryPort, eventPublisher);
    }

    // ---------------------------------------------------------------- 辅助：构造已过账领料单

    /**
     * 构造并存储一张已过账（COMPLETED）的领料单，包含 issuedCost 已回填的行。
     *
     * @param miDocNo    领料单号
     * @param productId  子件商品 id
     * @param qty        实领量
     * @param issuedCost 已过账领料成本（如 120.00）
     */
    private MaterialIssue buildCompletedMaterialIssue(String miDocNo, long productId,
                                                        BigDecimal qty, BigDecimal issuedCost) {
        // 使用 restore 工厂直接构造已过账领料行（含 issuedCost）
        MaterialIssueLine line = MaterialIssueLine.restore(
                1L, 1, productId,
                qty, qty, 1L, issuedCost);
        MaterialIssue mi = MaterialIssue.restore(
                miDocNo, "WO-001", 1L, null,
                DocumentStatus.COMPLETED, List.of(line), "op");
        miRepo.add(mi);
        return mi;
    }

    // ---------------------------------------------------------------- 建单校验

    @Test
    void create_原领料单不存在_抛异常() {
        List<MaterialReturnLineInput> lines = List.of(
                new MaterialReturnLineInput(201L, new BigDecimal("2"), 1L, 1));

        assertThatThrownBy(() -> service.create("MR-001", "MI-NOTEXIST", 1L, null, lines, "op"))
                .isInstanceOf(MaterialIssueNotFoundException.class);
    }

    @Test
    void create_原领料单非COMPLETED_抛异常() {
        // 构造 DRAFT 领料单
        MaterialIssueLine line = MaterialIssueLine.create(1, 201L,
                new BigDecimal("5"), new BigDecimal("4"), 1L);
        MaterialIssue draftMi = MaterialIssue.restore(
                "MI-001", "WO-001", 1L, null,
                DocumentStatus.DRAFT, List.of(line), "op");
        miRepo.add(draftMi);

        List<MaterialReturnLineInput> lines = List.of(
                new MaterialReturnLineInput(201L, new BigDecimal("2"), 1L, 1));

        assertThatThrownBy(() -> service.create("MR-001", "MI-001", 1L, null, lines, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尚未过账");
    }

    @Test
    void create_退料量超过原领料实领量_抛异常() {
        // 原领料实领 4 件，退 5 件 → 超退拒（评审 P1）
        buildCompletedMaterialIssue("MI-001", 201L, new BigDecimal("4"), new BigDecimal("120.00"));
        List<MaterialReturnLineInput> lines = List.of(
                new MaterialReturnLineInput(201L, new BigDecimal("5"), 1L, 1));

        assertThatThrownBy(() -> service.create("MR-001", "MI-001", 1L, null, lines, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超退");
    }

    @Test
    void create_退料商品不在原领料单_抛异常() {
        // 原领料只领了 201，退 999 → 拒（评审 P2：不可凭空退非领料商品）
        buildCompletedMaterialIssue("MI-001", 201L, new BigDecimal("4"), new BigDecimal("120.00"));
        List<MaterialReturnLineInput> lines = List.of(
                new MaterialReturnLineInput(999L, new BigDecimal("1"), 1L, 1));

        assertThatThrownBy(() -> service.create("MR-001", "MI-001", 1L, null, lines, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不在原领料单");
    }

    // ---------------------------------------------------------------- 正常建单与审核

    @Test
    void create_成功_状态为DRAFT() {
        buildCompletedMaterialIssue("MI-001", 201L, new BigDecimal("4"), new BigDecimal("120.00"));
        List<MaterialReturnLineInput> lines = List.of(
                new MaterialReturnLineInput(201L, new BigDecimal("2"), 1L, 1));

        MaterialReturn mr = service.create("MR-001", "MI-001", 1L, "退料备注", lines, "op");

        assertThat(mr.getStatus()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(mr.getMaterialIssueDocNo()).isEqualTo("MI-001");
        assertThat(mr.getLines()).hasSize(1);
    }

    @Test
    void approve_从DRAFT到APPROVED() {
        buildCompletedMaterialIssue("MI-001", 201L, new BigDecimal("4"), new BigDecimal("120.00"));
        List<MaterialReturnLineInput> lines = List.of(
                new MaterialReturnLineInput(201L, new BigDecimal("2"), 1L, 1));
        service.create("MR-001", "MI-001", 1L, null, lines, "op");

        MaterialReturn approved = service.approve("MR-001", "op");

        assertThat(approved.getStatus()).isEqualTo(DocumentStatus.APPROVED);
    }

    // ---------------------------------------------------------------- 过账：按原领料单价退回

    @Test
    void post_按原领料单价退回_returnedCost回填() {
        // 原领料单：4 件，issuedCost = 120.00，单价 = 120 / 4 = 30.000000
        buildCompletedMaterialIssue("MI-001", 201L, new BigDecimal("4.000000"),
                new BigDecimal("120.00"));
        List<MaterialReturnLineInput> lines = List.of(
                new MaterialReturnLineInput(201L, new BigDecimal("2"), 1L, 1));
        service.create("MR-001", "MI-001", 1L, null, lines, "op");
        service.approve("MR-001", "op");

        MaterialReturn posted = service.post("MR-001", "op");

        assertThat(posted.getStatus()).isEqualTo(DocumentStatus.COMPLETED);

        // 校验传入 InventoryPostingPort 的 unitCost = 30.000000（6 位精度）
        assertThat(inventoryPort.captured).hasSize(1);
        InboundCommand cmd = inventoryPort.captured.get(0);
        assertThat(cmd.unitCost()).isEqualByComparingTo("30.000000");

        // returnedCost = 2 × 30 = 60.00
        assertThat(posted.getLines().get(0).getReturnedCost())
                .isEqualByComparingTo("60.00");
    }

    // ---------------------------------------------------------------- 查询

    @Test
    void get_不存在_抛MaterialReturnNotFoundException() {
        assertThatThrownBy(() -> service.get("MR-NOTEXIST"))
                .isInstanceOf(MaterialReturnNotFoundException.class);
    }
}
