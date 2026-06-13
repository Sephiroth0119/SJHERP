package com.sjherp.app.collection;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.domain.collection.CollectionReceipt;
import com.sjherp.domain.collection.CollectionReceiptLine;
import com.sjherp.domain.collection.CollectionReceiptNotFoundException;
import com.sjherp.domain.collection.CollectionReceiptQuery;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.common.OverSettlementException;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.receivable.ReceivableNotFoundException;

/**
 * CollectionReceiptController MockMvc 切片测试（M4-T04b，照 {@code GlVoucherControllerTest} /
 * {@code PaymentAccountControllerTest} 范式）。
 *
 * <p>使用 {@code MockMvcBuilders.standaloneSetup}，{@link CollectionExceptionHandler} 通过
 * {@code setControllerAdvice} 接入，验证异常→状态码映射与金额字符串契约；{@code @PreAuthorize}
 * 在 standaloneSetup 中不生效，鉴权（401/403/放行）由 {@code CollectionReceiptApiPermissionTest}
 * （@WebMvcTest + 真 SecurityConfig）覆盖。
 *
 * <p>错误码契约（CollectionExceptionHandler）：收款单/应收不存在→404；关账期/非法流转→409；
 * 超额核销/跨客户/校验失败→400。
 */
class CollectionReceiptControllerTest {

    private static final String DOC_NO = "RCPT-202606-0001";

    private CollectionReceiptAppService appService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        appService = Mockito.mock(CollectionReceiptAppService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CollectionReceiptController(appService))
                .setControllerAdvice(new CollectionExceptionHandler())
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

    /** 从 restore 工厂重建收款单 stub（两行，总额 500.00） */
    private static CollectionReceipt receipt(DocumentStatus status) {
        List<CollectionReceiptLine> lines = List.of(
                CollectionReceiptLine.restore(11L, 1, 100L, new BigDecimal("300.00")),
                CollectionReceiptLine.restore(12L, 2, 200L, new BigDecimal("200.00")));
        return CollectionReceipt.restore(DOC_NO, 7L, 3L, java.time.LocalDate.of(2026, 6, 14),
                "回款", status, lines, "alice");
    }

    private static final String CREATE_JSON = """
            {
                "customerId": 7,
                "paymentAccountId": 3,
                "receiptDate": "2026-06-14",
                "remark": "回款",
                "lines": [
                    {"receivableId": 100, "allocatedAmount": "300.00"},
                    {"receivableId": 200, "allocatedAmount": "200.00"}
                ]
            }
            """;

    // ================================================================ 1. 建单

    @Test
    void 建单成功_201_金额字段为字符串() throws Exception {
        Mockito.when(appService.create(eq(7L), eq(3L), any(), eq("回款"), any(), anyString()))
                .thenReturn(receipt(DocumentStatus.DRAFT));

        mockMvc.perform(post("/api/collections")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.docNo").value(DOC_NO))
                .andExpect(jsonPath("$.customerId").value(7))
                .andExpect(jsonPath("$.paymentAccountId").value(3))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                // 精度契约：金额为字符串
                .andExpect(jsonPath("$.totalAmount").value("500.00"))
                .andExpect(jsonPath("$.lines[0].allocatedAmount").value("300.00"))
                .andExpect(jsonPath("$.lines[0].receivableId").value(100))
                .andExpect(jsonPath("$.lines[1].allocatedAmount").value("200.00"));
    }

    @Test
    void 建单缺customerId_400_BeanValidation() throws Exception {
        mockMvc.perform(post("/api/collections")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"paymentAccountId": 3,
                                 "lines": [{"receivableId":100,"allocatedAmount":"100.00"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(appService);
    }

    @Test
    void 建单空行集合_400_BeanValidation() throws Exception {
        mockMvc.perform(post("/api/collections")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"customerId":7,"paymentAccountId":3,"lines":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(appService);
    }

    @Test
    void 建单行缺分摊金额_400() throws Exception {
        mockMvc.perform(post("/api/collections")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"customerId":7,"paymentAccountId":3,
                                 "lines":[{"receivableId":100}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(appService);
    }

    @Test
    void 建单请求体非法JSON_400() throws Exception {
        mockMvc.perform(post("/api/collections")
                        .contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(appService);
    }

    // ================================================================ 2. 审核 / 过账

    @Test
    void 审核_200() throws Exception {
        Mockito.when(appService.approve(eq(DOC_NO), anyString()))
                .thenReturn(receipt(DocumentStatus.APPROVED));
        mockMvc.perform(post("/api/collections/" + DOC_NO + "/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void 过账_200_状态COMPLETED() throws Exception {
        Mockito.when(appService.post(eq(DOC_NO), anyString()))
                .thenReturn(receipt(DocumentStatus.COMPLETED));
        mockMvc.perform(post("/api/collections/" + DOC_NO + "/post"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalAmount").value("500.00"));
    }

    // ================================================================ 3. 错误码映射

    @Test
    void 收款单不存在_404() throws Exception {
        Mockito.when(appService.get("RCPT-NONE"))
                .thenThrow(new CollectionReceiptNotFoundException("RCPT-NONE"));
        mockMvc.perform(get("/api/collections/RCPT-NONE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void 过账引用应收不存在_404() throws Exception {
        Mockito.when(appService.post(eq(DOC_NO), anyString()))
                .thenThrow(new ReceivableNotFoundException(999L));
        mockMvc.perform(post("/api/collections/" + DOC_NO + "/post"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void 过账落关账期_409() throws Exception {
        Mockito.when(appService.post(eq(DOC_NO), anyString()))
                .thenThrow(new PeriodClosedException("202606"));
        mockMvc.perform(post("/api/collections/" + DOC_NO + "/post"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void 过账非法状态流转_409() throws Exception {
        Mockito.when(appService.post(eq(DOC_NO), anyString()))
                .thenThrow(new IllegalStateTransitionException(DOC_NO, DocumentStatus.COMPLETED,
                        DocumentStatus.EXECUTING));
        mockMvc.perform(post("/api/collections/" + DOC_NO + "/post"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void 过账超额核销_400() throws Exception {
        Mockito.when(appService.post(eq(DOC_NO), anyString()))
                .thenThrow(new OverSettlementException(new BigDecimal("300.00"),
                        new BigDecimal("0.00"), new BigDecimal("100.00")));
        mockMvc.perform(post("/api/collections/" + DOC_NO + "/post"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void 过账跨客户核销_400() throws Exception {
        Mockito.when(appService.post(eq(DOC_NO), anyString()))
                .thenThrow(new IllegalArgumentException("禁止跨客户核销"));
        mockMvc.perform(post("/api/collections/" + DOC_NO + "/post"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("禁止跨客户核销"));
    }

    // ================================================================ 4. 分页查询

    @Test
    void 分页查询_200_金额字符串() throws Exception {
        Mockito.when(appService.search(any(CollectionReceiptQuery.class)))
                .thenReturn(new PageResult<>(List.of(receipt(DocumentStatus.COMPLETED)), 1L, 1, 20));
        mockMvc.perform(get("/api/collections?customerId=7&page=1&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].docNo").value(DOC_NO))
                .andExpect(jsonPath("$.items[0].totalAmount").value("500.00"));
    }

    @Test
    void 分页查询_status过滤参数非法_400() throws Exception {
        mockMvc.perform(get("/api/collections?status=NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verify(appService, Mockito.never()).search(any());
    }

    @Test
    void 分页查询_customerId非数字_400() throws Exception {
        mockMvc.perform(get("/api/collections?customerId=abc"))
                .andExpect(status().isBadRequest());
        Mockito.verify(appService, Mockito.never()).search(any());
    }

    @Test
    void 分页查询_status大小写归一_200() throws Exception {
        Mockito.when(appService.search(any(CollectionReceiptQuery.class)))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 20));
        mockMvc.perform(get("/api/collections?status=completed"))
                .andExpect(status().isOk());
        // 归一后传入 DocumentStatus.COMPLETED
        var captor = org.mockito.ArgumentCaptor.forClass(CollectionReceiptQuery.class);
        Mockito.verify(appService).search(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(DocumentStatus.COMPLETED,
                captor.getValue().status());
    }
}
