package com.sjherp.app.purchase;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.app.security.PermissionGuard;
import com.sjherp.app.security.SecurityConfig;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.purchase.PurchaseReceiptLine;
import com.sjherp.domain.purchase.PurchaseReceiptQuery;

/**
 * T02g 采购发票入库单候选窄读投影权限与过滤验收。
 *
 * <p>ACCOUNTANT 只有 {@code purchase:invoice}、没有 {@code purchase:receipt}，仍必须能选择真实已过账
 * 入库单登记发票；投影不得泄露草稿、已冲销或已开完入库单，也不得放宽入库写权限。
 */
@WebMvcTest(controllers = PurchaseInvoiceController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class, PurchaseExceptionHandler.class})
class PurchaseInvoiceReceiptOptionApiPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PurchaseInvoiceAppService purchaseInvoiceAppService;
    @MockitoBean
    private PurchaseReceiptAppService purchaseReceiptAppService;
    @MockitoBean
    private UserRepository userRepository;

    private PurchaseReceipt completedWithOpenLine;
    private PurchaseReceipt completedFullyInvoiced;
    private PurchaseReceipt draft;
    private PurchaseReceipt reversed;

    @BeforeEach
    void setUp() {
        completedWithOpenLine = receipt("PR-COMPLETED-OPEN", DocumentStatus.COMPLETED,
                line(1, "10", "2", "4"), line(2, "5", "3", "5"));
        completedFullyInvoiced = receipt("PR-COMPLETED-FULL", DocumentStatus.COMPLETED,
                line(1, "8", "1", "8"));
        draft = receipt("PR-DRAFT", DocumentStatus.DRAFT, line(1, "3", "1", "0"));
        reversed = receipt("PR-REVERSED", DocumentStatus.REVERSED, line(1, "4", "1", "0"));

        Mockito.when(purchaseReceiptAppService.search(any()))
                .thenReturn(new PageResult<>(List.of(completedWithOpenLine), 1L, 1, 20));
        Mockito.when(purchaseReceiptAppService.get("PR-COMPLETED-OPEN"))
                .thenReturn(completedWithOpenLine);
        Mockito.when(purchaseReceiptAppService.get("PR-COMPLETED-FULL"))
                .thenReturn(completedFullyInvoiced);
        Mockito.when(purchaseReceiptAppService.get("PR-DRAFT")).thenReturn(draft);
        Mockito.when(purchaseReceiptAppService.get("PR-REVERSED")).thenReturn(reversed);
    }

    @Test
    void 会计仅凭采购发票权限可查候选_且只返回已过账入库单未开完行() throws Exception {
        mockMvc.perform(get("/api/purchase/invoices/receipt-options")
                        .param("page", "1").param("size", "20")
                        .with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].docNo").value("PR-COMPLETED-OPEN"))
                .andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].lines.length()").value(1))
                .andExpect(jsonPath("$.items[0].lines[0].receiptLineNo").value(1))
                .andExpect(jsonPath("$.items[0].lines[0].invoicedQty").value("4.000000"))
                .andExpect(jsonPath("$.items[0].lines[0].outstandingInvoiceableQty").value("6.000000"));

        ArgumentCaptor<PurchaseReceiptQuery> queryCaptor =
                ArgumentCaptor.forClass(PurchaseReceiptQuery.class);
        Mockito.verify(purchaseReceiptAppService).search(queryCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(queryCaptor.getValue().status())
                .isEqualTo(DocumentStatus.COMPLETED);
        org.assertj.core.api.Assertions.assertThat(queryCaptor.getValue().invoiceableOnly()).isTrue();
    }

    @Test
    void 会计可查单个候选详情_响应仍只含未开完行() throws Exception {
        mockMvc.perform(get("/api/purchase/invoices/receipt-options/PR-COMPLETED-OPEN")
                        .with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docNo").value("PR-COMPLETED-OPEN"))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].productId").value(101))
                .andExpect(jsonPath("$.lines[0].unitCost").value("2.000000"));
    }

    @Test
    void 草稿已冲销与已开完入库单均不可经候选详情读取() throws Exception {
        for (String docNo : List.of("PR-DRAFT", "PR-REVERSED", "PR-COMPLETED-FULL")) {
            mockMvc.perform(get("/api/purchase/invoices/receipt-options/{docNo}", docNo)
                            .with(asUser(Role.ACCOUNTANT)))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void 无采购发票权限角色查候选403且不调用入库服务() throws Exception {
        Mockito.clearInvocations(purchaseReceiptAppService);

        mockMvc.perform(get("/api/purchase/invoices/receipt-options")
                        .with(asUser(Role.WAREHOUSE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));

        Mockito.verifyNoInteractions(purchaseReceiptAppService);
    }

    @Test
    void 未登录查候选401() throws Exception {
        mockMvc.perform(get("/api/purchase/invoices/receipt-options"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    private static RequestPostProcessor asUser(Role... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "tester", "测试用户", Set.of(roles));
        var authorities = Set.of(roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    private static PurchaseReceipt receipt(String docNo, DocumentStatus status,
                                           PurchaseReceiptLine... lines) {
        return PurchaseReceipt.restore(docNo, "PO-SOURCE", 9L, LocalDate.of(2026, 7, 26),
                null, status, List.of(lines), "tester");
    }

    private static PurchaseReceiptLine line(int lineNo, String quantity, String unitCost,
                                            String invoicedQty) {
        BigDecimal qty = new BigDecimal(quantity).setScale(6);
        BigDecimal cost = new BigDecimal(unitCost).setScale(6);
        return PurchaseReceiptLine.restore(lineNo, lineNo, lineNo, 100L + lineNo, qty, cost,
                qty.multiply(cost).setScale(2), new BigDecimal(invoicedQty).setScale(6));
    }
}
