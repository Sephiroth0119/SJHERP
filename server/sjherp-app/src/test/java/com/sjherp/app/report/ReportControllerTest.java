package com.sjherp.app.report;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sjherp.app.report.ReportQueryDao.InventoryBalanceReport;
import com.sjherp.app.report.ReportQueryDao.InventoryBalanceRow;
import com.sjherp.app.report.ReportQueryDao.PurchaseDetailReport;
import com.sjherp.app.report.ReportQueryDao.PurchaseDetailRow;
import com.sjherp.app.report.ReportQueryDao.SalesDetailReport;
import com.sjherp.app.report.ReportQueryDao.SalesDetailRow;
import com.sjherp.app.report.ReportQueryDao.StockMovementReport;
import com.sjherp.app.report.ReportQueryDao.StockMovementRow;
import com.sjherp.app.report.ReportQueryDao.StockMovementSummary;
import com.sjherp.app.report.ReportQueryDao.SalesDetailSummary;
import com.sjherp.domain.common.PageResult;

/**
 * ReportController MockMvc 切片测试（M3-T12）。
 *
 * <p>使用 {@code MockMvcBuilders.standaloneSetup} 直接装配控制器，不启动 Spring 上下文，
 * 不触发 {@code @PreAuthorize}——本类只验证：参数绑定、精度契约（金额/数量为字符串）、
 * 必填参数缺失 400、起止日期倒序 400（{@link ReportController#onIllegalArgument} 的 {@code @ExceptionHandler}）。
 * 权限（403/401）在 {@code @WebMvcTest} + 真实 SecurityConfig 的专项测试中覆盖。
 */
class ReportControllerTest {

    private ReportQueryDao dao;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dao = Mockito.mock(ReportQueryDao.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ReportController(dao))
                .build();
    }

    // ================================================================ 辅助构造方法

    /** 构造一行库存余额（数量 100.000000 / 金额 1200.00 / 派生单价 12.000000）。 */
    private static InventoryBalanceReport oneRowBalanceReport() {
        InventoryBalanceRow row = new InventoryBalanceRow(
                1L, "WH-001", "一号仓",
                2L, "SKU-001", "不锈钢板 304L",
                new BigDecimal("100.000000"), new BigDecimal("1200.00"));
        return new InventoryBalanceReport(
                new PageResult<>(List.of(row), 1L, 1, 20),
                new BigDecimal("1200.00"));
    }

    /** 构造一行收发存汇总。 */
    private static StockMovementReport oneRowMovementReport() {
        StockMovementRow row = new StockMovementRow(
                1L, "WH-001", "一号仓",
                2L, "SKU-001", "不锈钢板 304L",
                new BigDecimal("50.000000"), new BigDecimal("600.00"),   // 期初
                new BigDecimal("100.000000"), new BigDecimal("1200.00"), // 收入
                new BigDecimal("30.000000"), new BigDecimal("360.00"),   // 发出
                new BigDecimal("120.000000"), new BigDecimal("1440.00")); // 期末
        StockMovementSummary summary = new StockMovementSummary(
                new BigDecimal("600.00"), new BigDecimal("1200.00"),
                new BigDecimal("360.00"), new BigDecimal("1440.00"));
        return new StockMovementReport(
                new PageResult<>(List.of(row), 1L, 1, 20),
                summary);
    }

    /** 构造一行采购明细（unitCost=12.00，quantity=100，amount=1200.00）。 */
    private static PurchaseDetailReport oneRowPurchaseReport() {
        PurchaseDetailRow row = new PurchaseDetailRow(
                "PR-202606-0001", LocalDate.of(2026, 6, 10), "PO-202606-0001",
                10L, "SUP-001", "钢铁供应商",
                1L, "WH-001", "一号仓",
                1, 2L, "SKU-001", "不锈钢板 304L",
                new BigDecimal("100.000000"), new BigDecimal("12.000000"),
                new BigDecimal("1200.00"), "COMPLETED");
        return new PurchaseDetailReport(
                new PageResult<>(List.of(row), 1L, 1, 20),
                new BigDecimal("1200.00"));
    }

    /** 构造一行销售明细（unitPrice=15.00，quantity=30，cogsAmount=360.00）。 */
    private static SalesDetailReport oneRowSalesReport() {
        SalesDetailRow row = new SalesDetailRow(
                "SD-202606-0001", LocalDate.of(2026, 6, 15), "SO-202606-0001",
                20L, "CUST-001", "优质客户",
                1L, "WH-001", "一号仓",
                1, 2L, "SKU-001", "不锈钢板 304L",
                new BigDecimal("30.000000"), new BigDecimal("15.000000"),
                new BigDecimal("360.00"), "COMPLETED");
        // salesAmount = 30 * 15 = 450.00；grossProfit = 450.00 - 360.00 = 90.00
        SalesDetailSummary summary = new SalesDetailSummary(
                new BigDecimal("450.00"), new BigDecimal("360.00"), new BigDecimal("90.00"));
        return new SalesDetailReport(
                new PageResult<>(List.of(row), 1L, 1, 20),
                summary);
    }

    // ================================================================ 1. 库存余额表

    /**
     * 正常请求：stub dao 返回一行，断言 200、JSON items[0] 中
     * quantity/costAmount/unitCost 均为字符串、totalCostAmount 为字符串。
     */
    @Test
    void 库存余额_正常请求_200_金额与数量为字符串() throws Exception {
        Mockito.when(dao.inventoryBalance(isNull(), isNull(), isNull(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(oneRowBalanceReport());

        mockMvc.perform(get("/api/reports/inventory-balance"))
                .andExpect(status().isOk())
                // 分页元信息
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                // 库存总值必须为字符串（精度契约）
                .andExpect(jsonPath("$.totalCostAmount").value("1200.00"))
                // 行级字段
                .andExpect(jsonPath("$.items[0].warehouseCode").value("WH-001"))
                .andExpect(jsonPath("$.items[0].productName").value("不锈钢板 304L"))
                // quantity 字符串
                .andExpect(jsonPath("$.items[0].quantity").value("100.000000"))
                // costAmount 字符串
                .andExpect(jsonPath("$.items[0].costAmount").value("1200.00"))
                // unitCost 为派生加权单价字符串（1200.00 / 100 = 12.000000，6 位 HALF_UP）
                .andExpect(jsonPath("$.items[0].unitCost").value("12.000000"));
    }

    /**
     * 显式传可选参数，断言 dao 被以对应 warehouseId 调用（参数绑定契约）。
     */
    @Test
    void 库存余额_传仓库id_参数绑定正确() throws Exception {
        Mockito.when(dao.inventoryBalance(Mockito.eq(1L), isNull(), isNull(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(oneRowBalanceReport());

        mockMvc.perform(get("/api/reports/inventory-balance?warehouseId=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        Mockito.verify(dao).inventoryBalance(Mockito.eq(1L), isNull(), isNull(),
                anyBoolean(), anyInt(), anyInt());
    }

    // ================================================================ 2. 收发存汇总

    /**
     * 正常请求（带 fromDate/toDate）：stub 返回，断言 200 + tie-out 字段存在且为字符串。
     */
    @Test
    void 收发存汇总_正常请求_200_合计字段为字符串() throws Exception {
        Mockito.when(dao.stockMovementSummary(
                        any(LocalDate.class), any(LocalDate.class),
                        isNull(), isNull(), isNull(),
                        anyInt(), anyInt()))
                .thenReturn(oneRowMovementReport());

        mockMvc.perform(get("/api/reports/stock-movement-summary?fromDate=2026-06-01&toDate=2026-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                // 合计金额为字符串
                .andExpect(jsonPath("$.totalOpeningAmount").value("600.00"))
                .andExpect(jsonPath("$.totalInAmount").value("1200.00"))
                .andExpect(jsonPath("$.totalOutAmount").value("360.00"))
                .andExpect(jsonPath("$.totalEndingAmount").value("1440.00"))
                // 行级：期初/收/发/期末 数量与金额均为字符串
                .andExpect(jsonPath("$.items[0].openingQuantity").value("50.000000"))
                .andExpect(jsonPath("$.items[0].openingAmount").value("600.00"))
                .andExpect(jsonPath("$.items[0].inQuantity").value("100.000000"))
                .andExpect(jsonPath("$.items[0].inAmount").value("1200.00"))
                .andExpect(jsonPath("$.items[0].outQuantity").value("30.000000"))
                .andExpect(jsonPath("$.items[0].outAmount").value("360.00"))
                .andExpect(jsonPath("$.items[0].endingQuantity").value("120.000000"))
                .andExpect(jsonPath("$.items[0].endingAmount").value("1440.00"));
    }

    /**
     * 缺 fromDate 必填参数 → 400（MissingServletRequestParameterException）。
     * standaloneSetup 默认注册了 DefaultHandlerExceptionResolver，会将其映射到 400。
     */
    @Test
    void 收发存汇总_缺fromDate_400() throws Exception {
        mockMvc.perform(get("/api/reports/stock-movement-summary?toDate=2026-06-30"))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(dao);
    }

    /**
     * 缺 toDate 必填参数 → 400。
     */
    @Test
    void 收发存汇总_缺toDate_400() throws Exception {
        mockMvc.perform(get("/api/reports/stock-movement-summary?fromDate=2026-06-01"))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(dao);
    }

    /**
     * fromDate > toDate（起止日期倒序）→ 400 且 body.error 非空
     * （控制器内 {@code requireRange} 抛 IllegalArgumentException，
     * {@link ReportController#onIllegalArgument} @ExceptionHandler 捕获后返回 400 + {"error":"..."}）。
     */
    @Test
    void 收发存汇总_起止日期倒序_400_错误体非空() throws Exception {
        mockMvc.perform(get("/api/reports/stock-movement-summary?fromDate=2026-06-30&toDate=2026-06-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());

        Mockito.verifyNoInteractions(dao);
    }

    /**
     * 合法日期范围（from == to 边界）→ 200（同一天 from == to 不触发倒序校验）。
     */
    @Test
    void 收发存汇总_fromDate等于toDate_200_不触发倒序校验() throws Exception {
        Mockito.when(dao.stockMovementSummary(
                        any(LocalDate.class), any(LocalDate.class),
                        isNull(), isNull(), isNull(),
                        anyInt(), anyInt()))
                .thenReturn(oneRowMovementReport());

        mockMvc.perform(get("/api/reports/stock-movement-summary?fromDate=2026-06-15&toDate=2026-06-15"))
                .andExpect(status().isOk());
    }

    // ================================================================ 3. 采购明细

    /**
     * 正常请求（不传任何可选参数）→ 200；stub 返回一行；断言金额字段为字符串、totalAmount 为字符串。
     * 验证可选参数全缺省时不报错（purchaseDetail 所有参数均可选）。
     */
    @Test
    void 采购明细_全可选参数缺省_200_金额为字符串() throws Exception {
        Mockito.when(dao.purchaseDetail(
                        isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                        anyInt(), anyInt()))
                .thenReturn(oneRowPurchaseReport());

        mockMvc.perform(get("/api/reports/purchase-detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                // 总进货额为字符串
                .andExpect(jsonPath("$.totalAmount").value("1200.00"))
                // 行级金额字段为字符串
                .andExpect(jsonPath("$.items[0].receiptNo").value("PR-202606-0001"))
                .andExpect(jsonPath("$.items[0].quantity").value("100.000000"))
                .andExpect(jsonPath("$.items[0].unitCost").value("12.000000"))
                .andExpect(jsonPath("$.items[0].amount").value("1200.00"))
                .andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].supplierName").value("钢铁供应商"));
    }

    /**
     * 传可选日期参数后 dao 被以正确参数调用（参数绑定 + 日期格式 ISO_DATE）。
     */
    @Test
    void 采购明细_传日期参数_参数绑定正确() throws Exception {
        Mockito.when(dao.purchaseDetail(
                        Mockito.eq(LocalDate.of(2026, 6, 1)),
                        Mockito.eq(LocalDate.of(2026, 6, 30)),
                        isNull(), isNull(), isNull(), isNull(),
                        anyInt(), anyInt()))
                .thenReturn(oneRowPurchaseReport());

        mockMvc.perform(get("/api/reports/purchase-detail?fromDate=2026-06-01&toDate=2026-06-30"))
                .andExpect(status().isOk());

        Mockito.verify(dao).purchaseDetail(
                Mockito.eq(LocalDate.of(2026, 6, 1)),
                Mockito.eq(LocalDate.of(2026, 6, 30)),
                isNull(), isNull(), isNull(), isNull(),
                anyInt(), anyInt());
    }

    /**
     * 采购明细日期倒序 → 400（from > to 触发 IllegalArgumentException）。
     */
    @Test
    void 采购明细_日期倒序_400() throws Exception {
        mockMvc.perform(get("/api/reports/purchase-detail?fromDate=2026-06-30&toDate=2026-06-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());

        Mockito.verifyNoInteractions(dao);
    }

    // ================================================================ 4. 销售明细

    /**
     * 正常请求（不传任何可选参数）→ 200；stub 返回一行；
     * 断言 salesAmount / cogsAmount / grossProfit 均为字符串（DTO 层现算）、合计亦为字符串。
     */
    @Test
    void 销售明细_全可选参数缺省_200_销售额毛利为字符串() throws Exception {
        Mockito.when(dao.salesDetail(
                        isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                        anyInt(), anyInt()))
                .thenReturn(oneRowSalesReport());

        mockMvc.perform(get("/api/reports/sales-detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                // 合计字段为字符串
                .andExpect(jsonPath("$.totalSalesAmount").value("450.00"))
                .andExpect(jsonPath("$.totalCogsAmount").value("360.00"))
                .andExpect(jsonPath("$.totalGrossProfit").value("90.00"))
                // 行级字段
                .andExpect(jsonPath("$.items[0].deliveryNo").value("SD-202606-0001"))
                .andExpect(jsonPath("$.items[0].quantity").value("30.000000"))
                .andExpect(jsonPath("$.items[0].unitPrice").value("15.000000"))
                // salesAmount = 30 * 15 = 450.00（2 位 HALF_UP 现算，字符串承载）
                .andExpect(jsonPath("$.items[0].salesAmount").value("450.00"))
                .andExpect(jsonPath("$.items[0].cogsAmount").value("360.00"))
                // grossProfit = 450.00 - 360.00 = 90.00
                .andExpect(jsonPath("$.items[0].grossProfit").value("90.00"))
                .andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].customerName").value("优质客户"));
    }

    /**
     * 销售明细传日期参数 → dao 被以正确日期参数调用。
     */
    @Test
    void 销售明细_传日期参数_参数绑定正确() throws Exception {
        Mockito.when(dao.salesDetail(
                        Mockito.eq(LocalDate.of(2026, 6, 1)),
                        Mockito.eq(LocalDate.of(2026, 6, 30)),
                        isNull(), isNull(), isNull(), isNull(),
                        anyInt(), anyInt()))
                .thenReturn(oneRowSalesReport());

        mockMvc.perform(get("/api/reports/sales-detail?fromDate=2026-06-01&toDate=2026-06-30"))
                .andExpect(status().isOk());

        Mockito.verify(dao).salesDetail(
                Mockito.eq(LocalDate.of(2026, 6, 1)),
                Mockito.eq(LocalDate.of(2026, 6, 30)),
                isNull(), isNull(), isNull(), isNull(),
                anyInt(), anyInt());
    }

    /**
     * 销售明细日期倒序 → 400。
     */
    @Test
    void 销售明细_日期倒序_400() throws Exception {
        mockMvc.perform(get("/api/reports/sales-detail?fromDate=2026-06-30&toDate=2026-06-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());

        Mockito.verifyNoInteractions(dao);
    }

    /**
     * 销售明细 unitPrice 为 null 时 salesAmount/grossProfit 应为 null（JSON 中字段值为 null）。
     * 验证 DTO 层「任一缺失则 null」的契约。
     */
    @Test
    void 销售明细_售价缺失_salesAmount和grossProfit为null() throws Exception {
        // unitPrice = null：订单行未关联
        SalesDetailRow rowNoPrice = new SalesDetailRow(
                "SD-202606-0002", LocalDate.of(2026, 6, 16), "SO-202606-0002",
                null, null, null,
                1L, "WH-001", "一号仓",
                1, 2L, "SKU-001", "不锈钢板 304L",
                new BigDecimal("10.000000"), null,
                new BigDecimal("120.00"), "COMPLETED");
        SalesDetailSummary summary = new SalesDetailSummary(
                BigDecimal.ZERO, new BigDecimal("120.00"), BigDecimal.ZERO.negate());
        SalesDetailReport report = new SalesDetailReport(
                new PageResult<>(List.of(rowNoPrice), 1L, 1, 20), summary);

        Mockito.when(dao.salesDetail(
                        isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                        anyInt(), anyInt()))
                .thenReturn(report);

        mockMvc.perform(get("/api/reports/sales-detail"))
                .andExpect(status().isOk())
                // unitPrice 缺失 → salesAmount 与 grossProfit 均为 null
                .andExpect(jsonPath("$.items[0].unitPrice").doesNotExist())
                .andExpect(jsonPath("$.items[0].salesAmount").doesNotExist())
                .andExpect(jsonPath("$.items[0].grossProfit").doesNotExist())
                // cogsAmount 有值
                .andExpect(jsonPath("$.items[0].cogsAmount").value("120.00"));
    }
}
