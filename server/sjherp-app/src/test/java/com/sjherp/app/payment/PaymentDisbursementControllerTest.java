package com.sjherp.app.payment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.common.OverSettlementException;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.fund.PaymentAccountNotFoundException;
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.payable.PayableNotFoundException;
import com.sjherp.domain.payment.PaymentDisbursement;
import com.sjherp.domain.payment.PaymentDisbursementNotFoundException;
import com.sjherp.domain.payment.PaymentDisbursementQuery;

/**
 * PaymentDisbursementController MockMvc 切片测试（M4-T04b，照 {@code PaymentAccountControllerTest} /
 * {@code SettlementControllerTest} 范式）。
 *
 * <p>用 {@code MockMvcBuilders.standaloneSetup}，{@link PaymentExceptionHandler} 经
 * {@code setControllerAdvice} 接入；{@code @PreAuthorize} 在 standaloneSetup 不生效，
 * 鉴权放行/403/401 由 {@code PaymentDisbursementApiPermissionTest}（@WebMvcTest 真 SecurityConfig）覆盖。
 *
 * <p>覆盖：4 端点（建/审/过账/查）+ 金额字符串序列化 + 错误码映射
 * （付款单/应付/资金账户不存在 404、PeriodClosed/非法流转 409、超额核销/跨供应商/校验 400）。
 */
class PaymentDisbursementControllerTest {

    private PaymentDisbursementAppService appService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        appService = Mockito.mock(PaymentDisbursementAppService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PaymentDisbursementController(appService))
                .setControllerAdvice(new PaymentExceptionHandler())
                .build();
        // standaloneSetup 不走 JWT 过滤器，直接注入认证态供控制器内 CurrentUser.operator() 解析登录名
        AuthenticatedUser principal = new AuthenticatedUser(1L, "alice", "爱丽丝", Set.of(Role.ACCOUNTANT));
        var token = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_ACCOUNTANT")));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 辅助

    /** 构造一张草稿付款单（领域工厂建好），方便 stub 返回值 */
    private static PaymentDisbursement draft(String docNo) {
        return PaymentDisbursement.create(docNo, 1L, 10L, LocalDate.of(2026, 6, 14), "付货款",
                List.of(line(1, 100L, "300.00"), line(2, 200L, "150.50")), "alice");
    }

    private static com.sjherp.domain.payment.PaymentDisbursementLine line(int lineNo, long payableId,
                                                                          String amount) {
        return com.sjherp.domain.payment.PaymentDisbursementLine.create(lineNo, payableId,
                new BigDecimal(amount));
    }

    // ================================================================ 1. 建单

    @Test
    void 建单_201_命令映射与字段序列化_金额字符串() throws Exception {
        Mockito.when(appService.create(anyLong(), anyLong(), any(), any(), any(), anyString()))
                .thenReturn(draft("PAYV-202606-0001"));

        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "supplierId": 1,
                                    "paymentAccountId": 10,
                                    "paymentDate": "2026-06-14",
                                    "remark": "付货款",
                                    "lines": [
                                        {"payableId": 100, "allocatedAmount": "300.00"},
                                        {"payableId": 200, "allocatedAmount": "150.50"}
                                    ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.docNo").value("PAYV-202606-0001"))
                .andExpect(jsonPath("$.supplierId").value(1))
                .andExpect(jsonPath("$.paymentAccountId").value(10))
                // 注：standaloneSetup 未注册 JavaTimeModule，LocalDate 序列化为数组——付款日的 ISO 字符串
                // 格式由 PaymentDisbursementApiPermissionTest（真实 Jackson 配置）覆盖；此处仅验证字段存在，
                // 精确日期值由下方 ArgumentCaptor 断言透传 LocalDate.of(2026,6,14)
                .andExpect(jsonPath("$.paymentDate").exists())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                // 金额字符串（精度契约）
                .andExpect(jsonPath("$.totalAmount").value("450.50"))
                .andExpect(jsonPath("$.lines[0].payableId").value(100))
                .andExpect(jsonPath("$.lines[0].allocatedAmount").value("300.00"))
                .andExpect(jsonPath("$.lines[1].allocatedAmount").value("150.50"));

        // 入参透传 + operator 取自认证态
        ArgumentCaptor<List<PaymentDtos.PaymentDisbursementLineRequest>> linesCaptor =
                ArgumentCaptor.forClass(List.class);
        Mockito.verify(appService).create(eq(1L), eq(10L), eq(LocalDate.of(2026, 6, 14)),
                eq("付货款"), linesCaptor.capture(), eq("alice"));
        org.assertj.core.api.Assertions.assertThat(linesCaptor.getValue()).hasSize(2);
    }

    @Test
    void 建单缺供应商_400_BeanValidation() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "paymentAccountId": 10,
                                    "lines": [{"payableId": 100, "allocatedAmount": "300.00"}]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(appService);
    }

    @Test
    void 建单空行_400_NotEmpty() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "supplierId": 1,
                                    "paymentAccountId": 10,
                                    "lines": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(appService);
    }

    // ================================================================ 2. 审核

    @Test
    void 审核_200() throws Exception {
        PaymentDisbursement approved = draft("PAYV-1");
        approved.approve("alice");
        Mockito.when(appService.approve(eq("PAYV-1"), anyString())).thenReturn(approved);

        mockMvc.perform(post("/api/payments/PAYV-1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        Mockito.verify(appService).approve("PAYV-1", "alice");
    }

    @Test
    void 审核_重复审核_409_非法流转() throws Exception {
        Mockito.when(appService.approve(anyString(), anyString())).thenThrow(
                new IllegalStateTransitionException("PAYV-1", DocumentStatus.APPROVED, DocumentStatus.APPROVED));

        mockMvc.perform(post("/api/payments/PAYV-1/approve"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ================================================================ 3. 过账

    @Test
    void 过账_200() throws Exception {
        PaymentDisbursement posted = draft("PAYV-1");
        posted.approve("alice");
        posted.startExecution("alice");
        posted.complete("alice");
        Mockito.when(appService.post(eq("PAYV-1"), anyString())).thenReturn(posted);

        mockMvc.perform(post("/api/payments/PAYV-1/post"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalAmount").value("450.50"));
        Mockito.verify(appService).post("PAYV-1", "alice");
    }

    @Test
    void 过账_应付不存在_404() throws Exception {
        Mockito.when(appService.post(anyString(), anyString()))
                .thenThrow(new PayableNotFoundException(100L));

        mockMvc.perform(post("/api/payments/PAYV-1/post"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void 过账_资金账户不存在_404() throws Exception {
        Mockito.when(appService.post(anyString(), anyString()))
                .thenThrow(PaymentAccountNotFoundException.account(10L));

        mockMvc.perform(post("/api/payments/PAYV-1/post"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void 过账_关账期_409() throws Exception {
        Mockito.when(appService.post(anyString(), anyString()))
                .thenThrow(new PeriodClosedException("202606"));

        mockMvc.perform(post("/api/payments/PAYV-1/post"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void 过账_跨供应商核销_400() throws Exception {
        Mockito.when(appService.post(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("禁止跨供应商核销"));

        mockMvc.perform(post("/api/payments/PAYV-1/post"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("跨供应商")));
    }

    @Test
    void 过账_超额核销_400() throws Exception {
        Mockito.when(appService.post(anyString(), anyString()))
                .thenThrow(new OverSettlementException(new BigDecimal("100.00"),
                        new BigDecimal("50.00"), new BigDecimal("80.00")));

        mockMvc.perform(post("/api/payments/PAYV-1/post"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ================================================================ 4. 查询

    @Test
    void 按单号查不存在_404() throws Exception {
        Mockito.when(appService.get("PAYV-NONE"))
                .thenThrow(new PaymentDisbursementNotFoundException("PAYV-NONE"));

        mockMvc.perform(get("/api/payments/PAYV-NONE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void 按单号查存在_200() throws Exception {
        Mockito.when(appService.get("PAYV-1")).thenReturn(draft("PAYV-1"));

        mockMvc.perform(get("/api/payments/PAYV-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docNo").value("PAYV-1"))
                .andExpect(jsonPath("$.totalAmount").value("450.50"));
    }

    @Test
    void 列表_200_分页结构_过滤透传() throws Exception {
        Mockito.when(appService.search(any(PaymentDisbursementQuery.class)))
                .thenReturn(new PageResult<>(List.of(draft("PAYV-1")), 1L, 1, 20));

        mockMvc.perform(get("/api/payments?supplierId=1&paymentAccountId=10&status=DRAFT&page=1&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items[0].docNo").value("PAYV-1"));

        ArgumentCaptor<PaymentDisbursementQuery> captor =
                ArgumentCaptor.forClass(PaymentDisbursementQuery.class);
        Mockito.verify(appService).search(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().supplierId()).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().paymentAccountId()).isEqualTo(10L);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().status()).isEqualTo(DocumentStatus.DRAFT);
    }

    @Test
    void 列表status参数非法_400() throws Exception {
        mockMvc.perform(get("/api/payments?status=FOO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(appService);
    }
}
