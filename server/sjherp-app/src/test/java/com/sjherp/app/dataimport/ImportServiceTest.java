package com.sjherp.app.dataimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.multipart.MultipartFile;

import com.sjherp.app.inventory.InventoryAdjustmentService;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductCommand;
import com.sjherp.domain.catalog.ProductQuery;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.catalog.Unit;
import com.sjherp.domain.catalog.UnitService;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.StockMovementResult;
import com.sjherp.domain.partner.CustomerCommand;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.domain.partner.SupplierCommand;
import com.sjherp.domain.partner.SupplierService;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseQuery;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * ImportService 校验失败报错单测（mockito，M2-T09 验收硬指标）：
 * <ul>
 *   <li>正常行调用对应写入口且操作人为登录名（非 agent: 前缀）</li>
 *   <li>领域抛 IllegalArgumentException → RowFailure 行号与 reason 正确、整体回滚</li>
 *   <li>数值格式非法/缺必填列 → 行级失败且 verifyNoInteractions(写入口)</li>
 *   <li>期初库存行映射到 opening 的参数（warehouseId/productId/quantity/unitCost）</li>
 * </ul>
 */
class ImportServiceTest {

    private static final String OPERATOR = "admin";

    private ProductService productService;
    private CustomerService customerService;
    private SupplierService supplierService;
    private WarehouseService warehouseService;
    private UnitService unitService;
    private InventoryAdjustmentService adjustmentService;
    private ImportService importService;

    @BeforeEach
    void setUp() {
        productService = mock(ProductService.class);
        customerService = mock(CustomerService.class);
        supplierService = mock(SupplierService.class);
        warehouseService = mock(WarehouseService.class);
        unitService = mock(UnitService.class);
        adjustmentService = mock(InventoryAdjustmentService.class);

        importService = new ImportService(productService, customerService, supplierService,
                warehouseService, unitService, adjustmentService);
    }

    // ====================================================================
    // 商品档案导入
    // ====================================================================

    @Test
    void 商品正常行_调用ProductService_create_操作人为登录名() throws IOException {
        // 用 ExcelTemplateWriter 生成含示例行的 xlsx
        byte[] xlsx = ExcelTemplateWriter.products();
        MultipartFile file = mockFile(xlsx);

        Unit unit = unit("千克");
        when(unitService.findAll()).thenReturn(List.of(unit));
        when(productService.create(any(), eq(OPERATOR))).thenReturn(product());

        var result = importService.importProducts(file, OPERATOR);

        assertThat(result.success()).isTrue();
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.succeeded()).isEqualTo(1);

        ArgumentCaptor<ProductCommand> captor = ArgumentCaptor.forClass(ProductCommand.class);
        verify(productService).create(captor.capture(), eq(OPERATOR));
        ProductCommand cmd = captor.getValue();
        assertThat(cmd.name()).isEqualTo("不锈钢板 304L");
        assertThat(cmd.baseUnitId()).isEqualTo(unit.getId());
    }

    @Test
    void 商品行领域拒绝编码重复_RowFailure行号与reason正确_整体回滚() throws IOException {
        byte[] xlsx = ExcelTemplateWriter.products();
        MultipartFile file = mockFile(xlsx);

        Unit unit = unit("千克");
        when(unitService.findAll()).thenReturn(List.of(unit));
        when(productService.create(any(), eq(OPERATOR)))
                .thenThrow(new IllegalArgumentException("商品编码已存在: SKU-001"));

        assertThatThrownBy(() -> importService.importProducts(file, OPERATOR))
                .isInstanceOf(ImportRejectedException.class)
                .satisfies(ex -> {
                    var rejected = (ImportRejectedException) ex;
                    var result = rejected.toResult();
                    assertThat(result.success()).isFalse();
                    assertThat(result.failures()).hasSize(1);
                    assertThat(result.failures().get(0).row()).isEqualTo(2); // 示例行是第2行
                    assertThat(result.failures().get(0).reason()).contains("商品编码已存在");
                    assertThat(result.succeeded()).isEqualTo(0);
                });
    }

    @Test
    void 商品行单位不存在_RowFailure_不触碰写入口() throws IOException {
        byte[] xlsx = ExcelTemplateWriter.products();
        MultipartFile file = mockFile(xlsx);

        // 单位列表为空，导致解析失败
        when(unitService.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> importService.importProducts(file, OPERATOR))
                .isInstanceOf(ImportRejectedException.class)
                .satisfies(ex -> {
                    var result = ((ImportRejectedException) ex).toResult();
                    assertThat(result.failures()).hasSize(1);
                    assertThat(result.failures().get(0).reason()).contains("基本单位");
                });

        verifyNoInteractions(productService);
    }

    // ====================================================================
    // 客户档案导入
    // ====================================================================

    @Test
    void 客户正常行_调用CustomerService_create_参数含SettlementMethod和登录名() throws IOException {
        byte[] xlsx = ExcelTemplateWriter.customers();
        MultipartFile file = mockFile(xlsx);

        when(customerService.create(any(), eq(OPERATOR))).thenReturn(null);

        var result = importService.importCustomers(file, OPERATOR);

        assertThat(result.success()).isTrue();
        ArgumentCaptor<CustomerCommand> captor = ArgumentCaptor.forClass(CustomerCommand.class);
        verify(customerService).create(captor.capture(), eq(OPERATOR));
        CustomerCommand cmd = captor.getValue();
        assertThat(cmd.name()).isEqualTo("示例客户公司");
        assertThat(cmd.settlementMethod()).isEqualTo(SettlementMethod.MONTHLY);
    }

    @Test
    void 客户结算方式非法_行级失败_不触碰写入口() throws IOException {
        // 构造含非法结算方式的 xlsx（在模板基础上手工构造）
        // 为简单起见，使用内联构造一个包含非法值的 xlsx
        byte[] xlsx = buildCustomerXlsx("CUS-001", "测试客户", "非法结算方式", null, null, null, null, null);
        MultipartFile file = mockFile(xlsx);

        assertThatThrownBy(() -> importService.importCustomers(file, OPERATOR))
                .isInstanceOf(ImportRejectedException.class)
                .satisfies(ex -> {
                    var result = ((ImportRejectedException) ex).toResult();
                    assertThat(result.failures()).hasSize(1);
                    assertThat(result.failures().get(0).reason()).contains("结算方式");
                });

        verifyNoInteractions(customerService);
    }

    // ====================================================================
    // 供应商档案导入
    // ====================================================================

    @Test
    void 供应商正常行_调用SupplierService_create_操作人为登录名() throws IOException {
        byte[] xlsx = ExcelTemplateWriter.suppliers();
        MultipartFile file = mockFile(xlsx);

        when(supplierService.create(any(), eq(OPERATOR))).thenReturn(null);

        var result = importService.importSuppliers(file, OPERATOR);

        assertThat(result.success()).isTrue();
        ArgumentCaptor<SupplierCommand> captor = ArgumentCaptor.forClass(SupplierCommand.class);
        verify(supplierService).create(captor.capture(), eq(OPERATOR));
        SupplierCommand cmd = captor.getValue();
        assertThat(cmd.name()).isEqualTo("示例供应商公司");
        assertThat(cmd.settlementMethod()).isEqualTo(SettlementMethod.MONTHLY);
    }

    // ====================================================================
    // 期初库存导入
    // ====================================================================

    @Test
    void 期初库存正常行_opening参数warehouseId_productId_quantity_unitCost() throws IOException {
        byte[] xlsx = ExcelTemplateWriter.openingStock();
        MultipartFile file = mockFile(xlsx);

        // 模板示例行：仓库=WH-001, 商品=SKU-001, 数量=100, 单价=10.00
        Warehouse wh = warehouse(1L, "WH-001", "测试仓");
        Product prod = product(2L, "SKU-001", "测试商品");
        when(warehouseService.search(any(WarehouseQuery.class)))
                .thenReturn(new PageResult<>(List.of(wh), 1, 1, 10));
        when(productService.search(any(ProductQuery.class)))
                .thenReturn(new PageResult<>(List.of(prod), 1, 1, 10));
        when(adjustmentService.opening(eq(1L), eq(2L), any(BigDecimal.class), any(BigDecimal.class),
                eq(OPERATOR))).thenReturn(openingResult());

        var result = importService.importOpeningStock(file, OPERATOR);

        assertThat(result.success()).isTrue();
        assertThat(result.succeeded()).isEqualTo(1);

        verify(adjustmentService).opening(
                eq(1L), eq(2L),
                eq(new BigDecimal("100")),
                eq(new BigDecimal("10.00")),
                eq(OPERATOR));
    }

    @Test
    void 期初库存数值格式非法_行级失败_不触碰写入口() throws IOException {
        byte[] xlsx = buildOpeningStockXlsx("WH-001", "SKU-001", "一百", "10.00");
        MultipartFile file = mockFile(xlsx);

        Warehouse wh = warehouse(1L, "WH-001", "测试仓");
        Product prod = product(2L, "SKU-001", "测试商品");
        when(warehouseService.search(any())).thenReturn(new PageResult<>(List.of(wh), 1, 1, 10));
        when(productService.search(any())).thenReturn(new PageResult<>(List.of(prod), 1, 1, 10));

        assertThatThrownBy(() -> importService.importOpeningStock(file, OPERATOR))
                .isInstanceOf(ImportRejectedException.class)
                .satisfies(ex -> {
                    var result = ((ImportRejectedException) ex).toResult();
                    assertThat(result.failures()).hasSize(1);
                    assertThat(result.failures().get(0).reason()).contains("数值格式不合法");
                });

        verifyNoInteractions(adjustmentService);
    }

    @Test
    void 期初库存数量为负_行级失败_不触碰写入口() throws IOException {
        byte[] xlsx = buildOpeningStockXlsx("WH-001", "SKU-001", "-10", "10.00");
        MultipartFile file = mockFile(xlsx);

        Warehouse wh = warehouse(1L, "WH-001", "测试仓");
        Product prod = product(2L, "SKU-001", "测试商品");
        when(warehouseService.search(any())).thenReturn(new PageResult<>(List.of(wh), 1, 1, 10));
        when(productService.search(any())).thenReturn(new PageResult<>(List.of(prod), 1, 1, 10));

        assertThatThrownBy(() -> importService.importOpeningStock(file, OPERATOR))
                .isInstanceOf(ImportRejectedException.class)
                .satisfies(ex -> {
                    var result = ((ImportRejectedException) ex).toResult();
                    assertThat(result.failures()).hasSize(1);
                    assertThat(result.failures().get(0).reason()).contains("必须为正数");
                });

        verifyNoInteractions(adjustmentService);
    }

    @Test
    void 期初库存仓库未找到_行级失败_不触碰调整服务() throws IOException {
        byte[] xlsx = buildOpeningStockXlsx("不存在仓库", "SKU-001", "100", "10.00");
        MultipartFile file = mockFile(xlsx);

        // 搜索结果为空
        when(warehouseService.search(any())).thenReturn(new PageResult<>(List.of(), 0, 1, 10));

        assertThatThrownBy(() -> importService.importOpeningStock(file, OPERATOR))
                .isInstanceOf(ImportRejectedException.class)
                .satisfies(ex -> {
                    var result = ((ImportRejectedException) ex).toResult();
                    assertThat(result.failures()).hasSize(1);
                    assertThat(result.failures().get(0).reason()).contains("仓库");
                });

        verifyNoInteractions(adjustmentService);
    }

    @Test
    void 期初库存领域拒绝_如幂等冲突_RowFailure_操作人登录名非agent前缀() throws IOException {
        byte[] xlsx = ExcelTemplateWriter.openingStock();
        MultipartFile file = mockFile(xlsx);

        Warehouse wh = warehouse(1L, "WH-001", "测试仓");
        Product prod = product(2L, "SKU-001", "测试商品");
        when(warehouseService.search(any())).thenReturn(new PageResult<>(List.of(wh), 1, 1, 10));
        when(productService.search(any())).thenReturn(new PageResult<>(List.of(prod), 1, 1, 10));
        when(adjustmentService.opening(anyLong(), anyLong(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("仓库已停用"));

        assertThatThrownBy(() -> importService.importOpeningStock(file, OPERATOR))
                .isInstanceOf(ImportRejectedException.class)
                .satisfies(ex -> {
                    var result = ((ImportRejectedException) ex).toResult();
                    assertThat(result.failures()).hasSize(1);
                    assertThat(result.failures().get(0).reason()).contains("仓库已停用");
                });

        // 操作人是登录名，非 agent: 前缀（导入是人工操作）
        verify(adjustmentService).opening(anyLong(), anyLong(), any(), any(), eq(OPERATOR));
    }

    // ====================================================================
    // 工具方法
    // ====================================================================

    private static MultipartFile mockFile(byte[] content) throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(content));
        return file;
    }

    private static Unit unit(String name) {
        return Unit.restore(10L, name, 3, "t", Instant.now(), "t", Instant.now());
    }

    private static Product product() {
        return product(2L, "SKU-001", "测试商品");
    }

    private static Product product(long id, String code, String name) {
        return Product.restore(id, code, name, null, null, 10L, null,
                ArchiveStatus.ENABLED, null, List.of(), "t", Instant.now(), "t", Instant.now());
    }

    private static Warehouse warehouse(long id, String code, String name) {
        return Warehouse.restore(id, code, name, null, null, false,
                ArchiveStatus.ENABLED, "t", Instant.now(), "t", Instant.now());
    }

    private static StockMovementResult openingResult() {
        return new StockMovementResult(1L, 1L, 2L, InventoryTxnType.OPENING,
                new BigDecimal("100.000000"), new BigDecimal("10.000000"), new BigDecimal("1000.00"),
                new BigDecimal("100.000000"), new BigDecimal("1000.00"),
                "OPENING", "OP-202606-0001", 1, "OPENING:OP-202606-0001:1");
    }

    /** 构造含单行数据的期初库存 xlsx（用于非模板默认值的参数化场景） */
    private static byte[] buildOpeningStockXlsx(String warehouse, String product,
                                                String quantity, String unitCost) {
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = wb.createSheet("导入数据");
            // 表头行
            var headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue(ImportColumns.OPENING_WAREHOUSE);
            headerRow.createCell(1).setCellValue(ImportColumns.OPENING_PRODUCT);
            headerRow.createCell(2).setCellValue(ImportColumns.OPENING_QUANTITY);
            headerRow.createCell(3).setCellValue(ImportColumns.OPENING_UNIT_COST);
            // 数据行
            var dataRow = sheet.createRow(1);
            dataRow.createCell(0).setCellValue(warehouse);
            dataRow.createCell(1).setCellValue(product);
            dataRow.createCell(2).setCellValue(quantity);
            dataRow.createCell(3).setCellValue(unitCost);

            var out = new java.io.ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("构造测试 xlsx 失败", e);
        }
    }

    /** 构造含单行数据的客户 xlsx */
    private static byte[] buildCustomerXlsx(String code, String name, String settlement,
                                            String contactPerson, String contactPhone,
                                            String address, String taxNo, String creditLimit) {
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = wb.createSheet("导入数据");
            var headerRow = sheet.createRow(0);
            String[] headers = ImportColumns.CUSTOMER_HEADERS;
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }
            var dataRow = sheet.createRow(1);
            dataRow.createCell(0).setCellValue(code == null ? "" : code);
            dataRow.createCell(1).setCellValue(name == null ? "" : name);
            dataRow.createCell(2).setCellValue(settlement == null ? "" : settlement);
            dataRow.createCell(3).setCellValue(contactPerson == null ? "" : contactPerson);
            dataRow.createCell(4).setCellValue(contactPhone == null ? "" : contactPhone);
            dataRow.createCell(5).setCellValue(address == null ? "" : address);
            dataRow.createCell(6).setCellValue(taxNo == null ? "" : taxNo);
            dataRow.createCell(7).setCellValue(creditLimit == null ? "" : creditLimit);

            var out = new java.io.ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("构造测试 xlsx 失败", e);
        }
    }
}
