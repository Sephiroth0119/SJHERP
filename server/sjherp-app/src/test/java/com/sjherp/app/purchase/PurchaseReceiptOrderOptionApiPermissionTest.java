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
import com.sjherp.domain.purchase.PurchaseOrder;
import com.sjherp.domain.purchase.PurchaseOrderLine;
import com.sjherp.domain.purchase.PurchaseOrderQuery;

/**
 * T02f 采购入库订单候选窄读投影权限与过滤验收。
 *
 * <p>WAREHOUSE 只有 {@code purchase:receipt}、没有 {@code purchase:order}，仍必须能选择真实已审核
 * 订单创建入库单；投影不得泄露草稿/已关闭订单或已收完行，也不得放宽采购订单写权限。
 */
@WebMvcTest(controllers = PurchaseReceiptController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class, PurchaseExceptionHandler.class})
class PurchaseReceiptOrderOptionApiPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PurchaseReceiptAppService purchaseReceiptAppService;
    @MockitoBean
    private PurchaseOrderAppService purchaseOrderAppService;
    @MockitoBean
    private UserRepository userRepository;

    private PurchaseOrder approvedWithOpenLine;
    private PurchaseOrder approvedFullyReceived;
    private PurchaseOrder draft;
    private PurchaseOrder completed;

    @BeforeEach
    void setUp() {
        approvedWithOpenLine = order("PO-APPROVED-OPEN", DocumentStatus.APPROVED,
                line(1, "10", "2", "4"), line(2, "5", "3", "5"));
        approvedFullyReceived = order("PO-APPROVED-FULL", DocumentStatus.APPROVED,
                line(1, "8", "1", "8"));
        draft = order("PO-DRAFT", DocumentStatus.DRAFT, line(1, "3", "1", "0"));
        completed = order("PO-COMPLETED", DocumentStatus.COMPLETED, line(1, "4", "1", "0"));

        Mockito.when(purchaseOrderAppService.search(any()))
                .thenReturn(new PageResult<>(
                        List.of(approvedWithOpenLine), 1L, 1, 20));
        Mockito.when(purchaseOrderAppService.get("PO-APPROVED-OPEN"))
                .thenReturn(approvedWithOpenLine);
        Mockito.when(purchaseOrderAppService.get("PO-APPROVED-FULL"))
                .thenReturn(approvedFullyReceived);
        Mockito.when(purchaseOrderAppService.get("PO-DRAFT")).thenReturn(draft);
        Mockito.when(purchaseOrderAppService.get("PO-COMPLETED")).thenReturn(completed);
    }

    @Test
    void 仓管仅凭采购入库权限可查候选_且只返回已审核订单未收行() throws Exception {
        mockMvc.perform(get("/api/purchase/receipts/order-options")
                        .param("page", "1").param("size", "20")
                        .with(asUser(Role.WAREHOUSE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].docNo").value("PO-APPROVED-OPEN"))
                .andExpect(jsonPath("$.items[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.items[0].lines.length()").value(1))
                .andExpect(jsonPath("$.items[0].lines[0].poLineNo").value(1))
                .andExpect(jsonPath("$.items[0].lines[0].receivedQty").value("4.000000"))
                .andExpect(jsonPath("$.items[0].lines[0].outstandingQty").value("6.000000"));

        ArgumentCaptor<PurchaseOrderQuery> queryCaptor = ArgumentCaptor.forClass(PurchaseOrderQuery.class);
        Mockito.verify(purchaseOrderAppService).search(queryCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(queryCaptor.getValue().status())
                .isEqualTo(DocumentStatus.APPROVED);
        org.assertj.core.api.Assertions.assertThat(queryCaptor.getValue().receivableOnly()).isTrue();
    }

    @Test
    void 仓管可查单个候选详情_响应仍只含未收行() throws Exception {
        mockMvc.perform(get("/api/purchase/receipts/order-options/PO-APPROVED-OPEN")
                        .with(asUser(Role.WAREHOUSE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docNo").value("PO-APPROVED-OPEN"))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].productId").value(101));
    }

    @Test
    void 草稿已关闭与已收完订单均不可经候选详情读取() throws Exception {
        for (String docNo : List.of("PO-DRAFT", "PO-COMPLETED", "PO-APPROVED-FULL")) {
            mockMvc.perform(get("/api/purchase/receipts/order-options/{docNo}", docNo)
                            .with(asUser(Role.WAREHOUSE)))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void 无采购入库权限角色查候选403且不调用订单服务() throws Exception {
        Mockito.clearInvocations(purchaseOrderAppService);

        mockMvc.perform(get("/api/purchase/receipts/order-options")
                        .with(asUser(Role.SALES)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));

        Mockito.verifyNoInteractions(purchaseOrderAppService);
    }

    @Test
    void 未登录查候选401() throws Exception {
        mockMvc.perform(get("/api/purchase/receipts/order-options"))
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

    private static PurchaseOrder order(String docNo, DocumentStatus status, PurchaseOrderLine... lines) {
        return PurchaseOrder.restore(docNo, 9L, LocalDate.of(2026, 7, 26), null,
                status, List.of(lines), "tester");
    }

    private static PurchaseOrderLine line(int lineNo, String quantity, String unitPrice,
                                          String receivedQty) {
        BigDecimal qty = new BigDecimal(quantity).setScale(6);
        BigDecimal price = new BigDecimal(unitPrice).setScale(6);
        return PurchaseOrderLine.restore(lineNo, lineNo, 100L + lineNo, qty, price,
                qty.multiply(price).setScale(2), new BigDecimal(receivedQty).setScale(6));
    }
}
