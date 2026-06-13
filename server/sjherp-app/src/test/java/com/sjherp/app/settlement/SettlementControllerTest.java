package com.sjherp.app.settlement;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sjherp.domain.settlement.SettlementRecord;
import com.sjherp.domain.settlement.SettlementType;

/**
 * SettlementController MockMvc 切片测试（M4-T03）。
 *
 * <p>使用 {@code MockMvcBuilders.standaloneSetup}，不触发 {@code @PreAuthorize}，验证
 * type=RECEIVABLE/PAYABLE 解析与大小写归一、type 非法/空 → 400 {"error":...}、targetId 透传、
 * 列表序列化（金额字符串、paymentDocNo 可空、type 名/日期字符串）。
 * 权限（401/403/200）由 {@code SettlementApiPermissionTest}（@WebMvcTest + 真实 SecurityConfig）覆盖。
 */
class SettlementControllerTest {

    private SettlementReadAppService appService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        appService = Mockito.mock(SettlementReadAppService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SettlementController(appService))
                .build();
    }

    // ================================================================ 辅助构造方法

    /** 应收核销记录（paymentDocNo=null，T03 恒空；amount=300.00）。 */
    private static SettlementRecord receivableRecord() {
        return SettlementRecord.restore(
                1L, SettlementType.RECEIVABLE, 100L, "AR-202606-0001",
                new BigDecimal("300.00"), LocalDate.of(2026, 6, 20), null,
                "alice", Instant.parse("2026-06-20T08:00:00Z"));
    }

    /** 应付核销记录（paymentDocNo 非空，模拟 T04 回填后场景；amount=150.00）。 */
    private static SettlementRecord payableRecordWithDocNo() {
        return SettlementRecord.restore(
                2L, SettlementType.PAYABLE, 200L, "AP-202606-0001",
                new BigDecimal("150.00"), LocalDate.of(2026, 6, 21), "PAY-202606-0001",
                "bob", Instant.parse("2026-06-21T09:30:00Z"));
    }

    // ================================================================ 1. 应收核销列表

    /**
     * type=RECEIVABLE → 委派 findReceivableSettlements(targetId)；
     * 列表序列化：金额字符串、日期字符串、type 名 RECEIVABLE、paymentDocNo 为 null（不渲染）。
     */
    @Test
    void 应收核销列表_RECEIVABLE_200_金额字符串_paymentDocNo缺省() throws Exception {
        Mockito.when(appService.findReceivableSettlements(100L))
                .thenReturn(List.of(receivableRecord()));

        mockMvc.perform(get("/api/settlements?type=RECEIVABLE&targetId=100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.items[0].type").value("RECEIVABLE"))
                .andExpect(jsonPath("$.items[0].targetId").value(100))
                .andExpect(jsonPath("$.items[0].targetSourceDocNo").value("AR-202606-0001"))
                // 金额字符串（精度契约）
                .andExpect(jsonPath("$.items[0].amount").value("300.00"))
                .andExpect(jsonPath("$.items[0].settlementDate").value("2026-06-20"))
                // T03 paymentDocNo 恒 null → Jackson 默认不渲染
                .andExpect(jsonPath("$.items[0].paymentDocNo").doesNotExist())
                .andExpect(jsonPath("$.items[0].createdBy").value("alice"));

        Mockito.verify(appService).findReceivableSettlements(100L);
        Mockito.verify(appService, Mockito.never()).findPayableSettlements(anyLong());
    }

    /**
     * type 小写 receivable → 归一为大写后正确解析（控制器 strip().toUpperCase()）。
     */
    @Test
    void 应收核销列表_type小写_归一解析_200() throws Exception {
        Mockito.when(appService.findReceivableSettlements(100L))
                .thenReturn(List.of(receivableRecord()));

        mockMvc.perform(get("/api/settlements?type=receivable&targetId=100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("RECEIVABLE"));

        Mockito.verify(appService).findReceivableSettlements(100L);
    }

    /**
     * 应收核销空列表 → 200，items 为空数组。
     */
    @Test
    void 应收核销列表_空_200_空数组() throws Exception {
        Mockito.when(appService.findReceivableSettlements(anyLong()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/settlements?type=RECEIVABLE&targetId=999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    // ================================================================ 2. 应付核销列表

    /**
     * type=PAYABLE → 委派 findPayableSettlements(targetId)；paymentDocNo 非空时正常渲染。
     */
    @Test
    void 应付核销列表_PAYABLE_200_paymentDocNo渲染() throws Exception {
        Mockito.when(appService.findPayableSettlements(200L))
                .thenReturn(List.of(payableRecordWithDocNo()));

        mockMvc.perform(get("/api/settlements?type=PAYABLE&targetId=200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(2))
                .andExpect(jsonPath("$.items[0].type").value("PAYABLE"))
                .andExpect(jsonPath("$.items[0].targetId").value(200))
                .andExpect(jsonPath("$.items[0].targetSourceDocNo").value("AP-202606-0001"))
                .andExpect(jsonPath("$.items[0].amount").value("150.00"))
                .andExpect(jsonPath("$.items[0].settlementDate").value("2026-06-21"))
                // paymentDocNo 非空时渲染
                .andExpect(jsonPath("$.items[0].paymentDocNo").value("PAY-202606-0001"))
                .andExpect(jsonPath("$.items[0].createdBy").value("bob"));

        Mockito.verify(appService).findPayableSettlements(200L);
        Mockito.verify(appService, Mockito.never()).findReceivableSettlements(anyLong());
    }

    // ================================================================ 3. type 非法 / 空

    /**
     * type 非法（既非 RECEIVABLE 也非 PAYABLE）→ 控制器 parseType 抛 IllegalArgumentException
     * → @ExceptionHandler 映射 400 + {"error":...}；不触碰 AppService。
     */
    @Test
    void type非法_400_错误体非空() throws Exception {
        mockMvc.perform(get("/api/settlements?type=INVALID&targetId=1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());

        Mockito.verifyNoInteractions(appService);
    }

    /**
     * type 为空白字符串 → 400（parseType 对 blank 直接拒绝）。
     */
    @Test
    void type空白_400() throws Exception {
        mockMvc.perform(get("/api/settlements?type=%20%20&targetId=1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());

        Mockito.verifyNoInteractions(appService);
    }

    /**
     * 缺必填 type 参数 → MissingServletRequestParameterException → 400。
     */
    @Test
    void 缺type参数_400() throws Exception {
        mockMvc.perform(get("/api/settlements?targetId=1"))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(appService);
    }

    /**
     * 缺必填 targetId 参数 → 400。
     */
    @Test
    void 缺targetId参数_400() throws Exception {
        mockMvc.perform(get("/api/settlements?type=RECEIVABLE"))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(appService);
    }

    /**
     * targetId 非数字 → 类型转换失败 → 400。
     */
    @Test
    void targetId非数字_400() throws Exception {
        mockMvc.perform(get("/api/settlements?type=RECEIVABLE&targetId=abc"))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(appService);
    }
}
