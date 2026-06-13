package com.sjherp.app.finance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sjherp.app.finance.AgingReportDao.AgingGrandTotal;
import com.sjherp.app.finance.AgingReportDao.AgingReport;
import com.sjherp.app.finance.AgingReportDao.AgingRow;
import com.sjherp.domain.common.PageResult;

/**
 * AgingReportController MockMvc 切片测试（M4-T03）。
 *
 * <p>使用 {@code MockMvcBuilders.standaloneSetup}，不启动 Spring 上下文、不触发 {@code @PreAuthorize}，
 * 只验证参数绑定、asOf 缺省/解析、过滤参数透传、精度契约（金额一律字符串）、桶字段 JSON 序列化、
 * 空集 grandTotal 归零、非法参数 → 400 {"error":...}（控制器 {@code @ExceptionHandler}）。
 * 权限（401/403/200）由 {@code AgingReportApiPermissionTest}（@WebMvcTest + 真实 SecurityConfig）覆盖。
 */
class AgingReportControllerTest {

    private AgingReportDao dao;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dao = Mockito.mock(AgingReportDao.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AgingReportController(dao))
                .build();
    }

    // ================================================================ 辅助构造方法

    /** 构造一行账龄：5 桶 100.00/200.00/300.00/400.00/500.00，合计 1500.00。 */
    private static AgingRow oneRow() {
        return new AgingRow(
                10L, "CUST-001", "优质客户",
                new BigDecimal("100.00"), new BigDecimal("200.00"), new BigDecimal("300.00"),
                new BigDecimal("400.00"), new BigDecimal("500.00"), new BigDecimal("1500.00"));
    }

    /** 与 oneRow 同口径的 grandTotal（单行时各桶 == 行桶）。 */
    private static AgingGrandTotal oneRowGrandTotal() {
        return new AgingGrandTotal(
                new BigDecimal("100.00"), new BigDecimal("200.00"), new BigDecimal("300.00"),
                new BigDecimal("400.00"), new BigDecimal("500.00"), new BigDecimal("1500.00"));
    }

    /** 单行账龄报表（asOf=2026-06-30，page=1，size=20，total=1）。 */
    private static AgingReport oneRowReport() {
        return new AgingReport(LocalDate.of(2026, 6, 30),
                new PageResult<>(List.of(oneRow()), 1L, 1, 20), oneRowGrandTotal());
    }

    /** 空集账龄报表：无行，各桶 grandTotal 归零（DAO 的 zeroGrandTotal 口径 ZERO=BigDecimal.ZERO）。 */
    private static AgingReport emptyReport(LocalDate asOf) {
        AgingGrandTotal zero = new AgingGrandTotal(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        return new AgingReport(asOf, new PageResult<>(List.of(), 0L, 1, 20), zero);
    }

    // ================================================================ 1. 应收账龄

    /**
     * 应收账龄正常请求（显式 asOf）→ 200；
     * 断言：asOf 解析正确、桶字段与 totalOutstanding 均为字符串、grandTotal 各桶为字符串、分页元信息。
     */
    @Test
    void 应收账龄_正常请求_200_桶字段与grandTotal为字符串() throws Exception {
        Mockito.when(dao.receivableAging(eq(LocalDate.of(2026, 6, 30)), isNull(), anyInt(), anyInt()))
                .thenReturn(oneRowReport());

        mockMvc.perform(get("/api/reports/receivable-aging?asOf=2026-06-30"))
                .andExpect(status().isOk())
                // 截止日按 ISO_DATE 字符串回显
                .andExpect(jsonPath("$.asOf").value("2026-06-30"))
                // 分页元信息
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                // 行级对手方
                .andExpect(jsonPath("$.items[0].counterpartyId").value(10))
                .andExpect(jsonPath("$.items[0].counterpartyCode").value("CUST-001"))
                .andExpect(jsonPath("$.items[0].counterpartyName").value("优质客户"))
                // 5 桶 + 合计均为字符串（精度契约）
                .andExpect(jsonPath("$.items[0].notDue").value("100.00"))
                .andExpect(jsonPath("$.items[0].overdue1To30").value("200.00"))
                .andExpect(jsonPath("$.items[0].overdue31To60").value("300.00"))
                .andExpect(jsonPath("$.items[0].overdue61To90").value("400.00"))
                .andExpect(jsonPath("$.items[0].overdue90Plus").value("500.00"))
                .andExpect(jsonPath("$.items[0].totalOutstanding").value("1500.00"))
                // grandTotal 各桶为字符串
                .andExpect(jsonPath("$.grandTotal.notDue").value("100.00"))
                .andExpect(jsonPath("$.grandTotal.overdue1To30").value("200.00"))
                .andExpect(jsonPath("$.grandTotal.overdue31To60").value("300.00"))
                .andExpect(jsonPath("$.grandTotal.overdue61To90").value("400.00"))
                .andExpect(jsonPath("$.grandTotal.overdue90Plus").value("500.00"))
                .andExpect(jsonPath("$.grandTotal.totalOutstanding").value("1500.00"));
    }

    /**
     * 应收账龄缺省 asOf → 控制器以 LocalDate.now() 缺省调用 DAO（不应为 null）。
     * 用 ArgumentCaptor 捕获实参，断言其为今天（与 LocalDate.now() 一致，按用例同日运行）。
     */
    @Test
    void 应收账龄_缺asOf_缺省今天透传DAO() throws Exception {
        LocalDate today = LocalDate.now();
        Mockito.when(dao.receivableAging(any(LocalDate.class), isNull(), anyInt(), anyInt()))
                .thenReturn(oneRowReport());

        mockMvc.perform(get("/api/reports/receivable-aging"))
                .andExpect(status().isOk());

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        Mockito.verify(dao).receivableAging(captor.capture(), isNull(), anyInt(), anyInt());
        // asOf 缺省今天，绝不为 null（DAO 的 asOf 占位不可空）
        org.junit.jupiter.api.Assertions.assertEquals(today, captor.getValue());
    }

    /**
     * 应收账龄传 customerId → 透传给 DAO（参数绑定契约）。
     */
    @Test
    void 应收账龄_传customerId_透传DAO() throws Exception {
        Mockito.when(dao.receivableAging(eq(LocalDate.of(2026, 6, 30)), eq(10L), anyInt(), anyInt()))
                .thenReturn(oneRowReport());

        mockMvc.perform(get("/api/reports/receivable-aging?asOf=2026-06-30&customerId=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        Mockito.verify(dao).receivableAging(eq(LocalDate.of(2026, 6, 30)), eq(10L), anyInt(), anyInt());
    }

    /**
     * 应收账龄传 page/size → 透传给 DAO（分页参数绑定）。
     */
    @Test
    void 应收账龄_传分页参数_透传DAO() throws Exception {
        Mockito.when(dao.receivableAging(any(LocalDate.class), isNull(), eq(2), eq(50)))
                .thenReturn(oneRowReport());

        mockMvc.perform(get("/api/reports/receivable-aging?page=2&size=50"))
                .andExpect(status().isOk());

        Mockito.verify(dao).receivableAging(any(LocalDate.class), isNull(), eq(2), eq(50));
    }

    /**
     * 应收账龄空集 → 200，items 为空数组、grandTotal 各桶序列化为 "0.0"（BigDecimal.ZERO.toPlainString()）。
     * 注意：DAO 空集 grandTotal 走 zeroGrandTotal() = BigDecimal.ZERO，其 toPlainString() 是 "0"，
     * 这里夹具直接给 BigDecimal.ZERO，断言序列化结果为 "0"（验证 plain() 不吞 0）。
     */
    @Test
    void 应收账龄_空集_200_grandTotal归零() throws Exception {
        Mockito.when(dao.receivableAging(any(LocalDate.class), isNull(), anyInt(), anyInt()))
                .thenReturn(emptyReport(LocalDate.of(2026, 6, 30)));

        mockMvc.perform(get("/api/reports/receivable-aging?asOf=2026-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0))
                // BigDecimal.ZERO.toPlainString() == "0"
                .andExpect(jsonPath("$.grandTotal.notDue").value("0"))
                .andExpect(jsonPath("$.grandTotal.totalOutstanding").value("0"));
    }

    /**
     * asOf 格式非法（非 ISO_DATE）→ standaloneSetup 的 DefaultHandlerExceptionResolver
     * 将类型转换失败映射为 400（不进入控制器方法，DAO 不被调用）。
     */
    @Test
    void 应收账龄_asOf格式非法_400() throws Exception {
        mockMvc.perform(get("/api/reports/receivable-aging?asOf=2026/06/30"))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(dao);
    }

    /**
     * customerId 非数字 → 类型转换失败 → 400。
     */
    @Test
    void 应收账龄_customerId非数字_400() throws Exception {
        mockMvc.perform(get("/api/reports/receivable-aging?customerId=abc"))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(dao);
    }

    /**
     * DAO 抛 IllegalArgumentException → 控制器 @ExceptionHandler 映射 400 + {"error":...}。
     */
    @Test
    void 应收账龄_DAO抛非法参数_400_错误体非空() throws Exception {
        Mockito.when(dao.receivableAging(any(LocalDate.class), isNull(), anyInt(), anyInt()))
                .thenThrow(new IllegalArgumentException("参数不合法"));

        mockMvc.perform(get("/api/reports/receivable-aging"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ================================================================ 2. 应付账龄

    /**
     * 应付账龄正常请求（显式 asOf + supplierId）→ 200；supplierId 透传 DAO，桶字段为字符串。
     */
    @Test
    void 应付账龄_正常请求_200_supplierId透传_桶字段为字符串() throws Exception {
        Mockito.when(dao.payableAging(eq(LocalDate.of(2026, 6, 30)), eq(20L), anyInt(), anyInt()))
                .thenReturn(oneRowReport());

        mockMvc.perform(get("/api/reports/payable-aging?asOf=2026-06-30&supplierId=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-06-30"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].notDue").value("100.00"))
                .andExpect(jsonPath("$.items[0].overdue90Plus").value("500.00"))
                .andExpect(jsonPath("$.items[0].totalOutstanding").value("1500.00"))
                .andExpect(jsonPath("$.grandTotal.totalOutstanding").value("1500.00"));

        Mockito.verify(dao).payableAging(eq(LocalDate.of(2026, 6, 30)), eq(20L), anyInt(), anyInt());
    }

    /**
     * 应付账龄缺省 asOf → 缺省今天透传（不为 null）。
     */
    @Test
    void 应付账龄_缺asOf_缺省今天透传DAO() throws Exception {
        LocalDate today = LocalDate.now();
        Mockito.when(dao.payableAging(any(LocalDate.class), isNull(), anyInt(), anyInt()))
                .thenReturn(oneRowReport());

        mockMvc.perform(get("/api/reports/payable-aging"))
                .andExpect(status().isOk());

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        Mockito.verify(dao).payableAging(captor.capture(), isNull(), anyInt(), anyInt());
        org.junit.jupiter.api.Assertions.assertEquals(today, captor.getValue());
    }

    /**
     * 对手方档案缺失（LEFT JOIN 不丢行）：counterpartyCode/Name 为 null，
     * JSON 中字段缺省（Jackson 默认不渲染 null）；行仍出现，金额桶有值。
     */
    @Test
    void 应付账龄_对手方档案缺失_行仍出_code与name为null() throws Exception {
        AgingRow rowNullCp = new AgingRow(
                99L, null, null,
                new BigDecimal("10.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10.00"));
        AgingGrandTotal gt = new AgingGrandTotal(
                new BigDecimal("10.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10.00"));
        AgingReport report = new AgingReport(LocalDate.of(2026, 6, 30),
                new PageResult<>(List.of(rowNullCp), 1L, 1, 20), gt);
        Mockito.when(dao.payableAging(any(LocalDate.class), isNull(), anyInt(), anyInt()))
                .thenReturn(report);

        mockMvc.perform(get("/api/reports/payable-aging?asOf=2026-06-30"))
                .andExpect(status().isOk())
                // 档案缺失行仍暴露
                .andExpect(jsonPath("$.items[0].counterpartyId").value(99))
                .andExpect(jsonPath("$.items[0].counterpartyCode").doesNotExist())
                .andExpect(jsonPath("$.items[0].counterpartyName").doesNotExist())
                .andExpect(jsonPath("$.items[0].totalOutstanding").value("10.00"));
    }
}
