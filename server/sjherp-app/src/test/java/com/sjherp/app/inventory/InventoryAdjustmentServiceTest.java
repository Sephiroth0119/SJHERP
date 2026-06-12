package com.sjherp.app.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sjherp.app.config.TransactionalInventoryService;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.inventory.CostAdjustCommand;
import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.StockMovementResult;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 库存调整应用服务单测（M3-T01c）：命令映射（OP-/CA- 编号、幂等键约定）、
 * 仓库/商品停用拒绝、必填参数校验。过账本身的口径在领域层测试覆盖。
 */
class InventoryAdjustmentServiceTest {

    private static final String OPERATOR = "tester";

    private TransactionalInventoryService inventoryService;
    private WarehouseService warehouseService;
    private ProductService productService;
    private DocumentNumberGenerator numberGenerator;
    private InventoryAdjustmentService service;

    @BeforeEach
    void setUp() {
        inventoryService = mock(TransactionalInventoryService.class);
        warehouseService = mock(WarehouseService.class);
        productService = mock(ProductService.class);
        numberGenerator = mock(DocumentNumberGenerator.class);
        service = new InventoryAdjustmentService(inventoryService, warehouseService,
                productService, numberGenerator);

        when(warehouseService.get(1L)).thenReturn(warehouse(ArchiveStatus.ENABLED));
        when(productService.get(2L)).thenReturn(product(ArchiveStatus.ENABLED));
    }

    private static Warehouse warehouse(ArchiveStatus status) {
        return Warehouse.restore(1L, "WH-202606-0001", "一号仓", null, null, false, status,
                "t", Instant.now(), "t", Instant.now());
    }

    private static Product product(ArchiveStatus status) {
        return Product.restore(2L, "SKU-202606-0001", "不锈钢板 304L", null, null, 1L, null,
                status, null, List.of(), "t", Instant.now(), "t", Instant.now());
    }

    private static StockMovementResult result(InventoryTxnType type, String docNo) {
        return new StockMovementResult(9L, 1L, 2L, type, new BigDecimal("100.000000"),
                new BigDecimal("10.000000"), new BigDecimal("1000.00"),
                new BigDecimal("100.000000"), new BigDecimal("1000.00"),
                type.name(), docNo, 1, type.name() + ":" + docNo + ":1");
    }

    @Test
    void 期初建账_OP编号_幂等键约定_命令映射() {
        when(numberGenerator.generate(InventoryAdjustmentService.OPENING_RULE))
                .thenReturn("OP-202606-0001");
        when(inventoryService.inbound(any(), eq(OPERATOR)))
                .thenReturn(result(InventoryTxnType.OPENING, "OP-202606-0001"));

        StockMovementResult result = service.opening(1L, 2L,
                new BigDecimal("100"), new BigDecimal("10.00"), OPERATOR);

        ArgumentCaptor<InboundCommand> captor = ArgumentCaptor.forClass(InboundCommand.class);
        verify(inventoryService).inbound(captor.capture(), eq(OPERATOR));
        InboundCommand command = captor.getValue();
        assertThat(command.warehouseId()).isEqualTo(1L);
        assertThat(command.productId()).isEqualTo(2L);
        assertThat(command.txnType()).isEqualTo(InventoryTxnType.OPENING);
        assertThat(command.quantity()).isEqualByComparingTo("100");
        assertThat(command.unitCost()).isEqualByComparingTo("10.00");
        assertThat(command.srcDocType()).isEqualTo("OPENING");
        assertThat(command.srcDocNo()).isEqualTo("OP-202606-0001");
        assertThat(command.srcLineNo()).isEqualTo(1);
        // 幂等键约定 docType:docNo:lineNo（拆解 §1.3）
        assertThat(command.idempotencyKey()).isEqualTo("OPENING:OP-202606-0001:1");
        assertThat(result.srcDocNo()).isEqualTo("OP-202606-0001");
    }

    @Test
    void 成本调整_CA编号_负调整额放行到领域层() {
        when(numberGenerator.generate(InventoryAdjustmentService.COST_ADJUST_RULE))
                .thenReturn("CA-202606-0001");
        when(inventoryService.adjustCost(any(), eq(OPERATOR)))
                .thenReturn(result(InventoryTxnType.COST_ADJUST, "CA-202606-0001"));

        service.costAdjust(1L, 2L, new BigDecimal("-5.00"), OPERATOR);

        ArgumentCaptor<CostAdjustCommand> captor = ArgumentCaptor.forClass(CostAdjustCommand.class);
        verify(inventoryService).adjustCost(captor.capture(), eq(OPERATOR));
        CostAdjustCommand command = captor.getValue();
        assertThat(command.adjustAmount()).isEqualByComparingTo("-5.00");
        assertThat(command.srcDocType()).isEqualTo("COST_ADJUST");
        assertThat(command.idempotencyKey()).isEqualTo("COST_ADJUST:CA-202606-0001:1");
    }

    @Test
    void 仓库停用_拒绝且不取号不过账() {
        when(warehouseService.get(1L)).thenReturn(warehouse(ArchiveStatus.DISABLED));

        assertThatThrownBy(() -> service.opening(1L, 2L,
                new BigDecimal("100"), new BigDecimal("10.00"), OPERATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仓库已停用");
        verifyNoInteractions(inventoryService, numberGenerator);
    }

    @Test
    void 商品停用_拒绝且不取号不过账() {
        when(productService.get(2L)).thenReturn(product(ArchiveStatus.DISABLED));

        assertThatThrownBy(() -> service.costAdjust(1L, 2L, new BigDecimal("5.00"), OPERATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("商品已停用");
        verifyNoInteractions(inventoryService, numberGenerator);
    }

    @Test
    void 期初建账_缺数量或单价拒绝() {
        assertThatThrownBy(() -> service.opening(1L, 2L, null, new BigDecimal("10.00"), OPERATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数量不能为空");
        assertThatThrownBy(() -> service.opening(1L, 2L, new BigDecimal("100"), null, OPERATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("单价不能为空");
        verifyNoInteractions(inventoryService);
    }

    @Test
    void 成本调整_缺调整额拒绝() {
        assertThatThrownBy(() -> service.costAdjust(1L, 2L, null, OPERATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("调整额不能为空");
        verifyNoInteractions(inventoryService);
    }
}
