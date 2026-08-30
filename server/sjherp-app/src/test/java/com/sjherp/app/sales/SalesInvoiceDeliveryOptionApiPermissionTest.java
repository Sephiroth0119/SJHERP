package com.sjherp.app.sales;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesDeliveryLine;
import com.sjherp.domain.sales.SalesDeliveryQuery;

/** T02i 销售发票出库单候选窄读投影权限与过滤验收。 */
@WebMvcTest(controllers = SalesInvoiceController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class, SalesExceptionHandler.class})
class SalesInvoiceDeliveryOptionApiPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SalesInvoiceAppService salesInvoiceAppService;
    @MockitoBean
    private SalesDeliveryAppService salesDeliveryAppService;
    @MockitoBean
    private UserRepository userRepository;

    private SalesDelivery completedWithOpenLine;

    @BeforeEach
    void setUp() {
        completedWithOpenLine = delivery("SD-COMPLETED-OPEN", DocumentStatus.COMPLETED,
                line(1, "10", "4"), line(2, "5", "5"));
        Mockito.when(salesDeliveryAppService.search(any()))
                .thenReturn(new PageResult<>(List.of(completedWithOpenLine), 1L, 1, 20));
        Mockito.when(salesDeliveryAppService.get(any())).thenAnswer(invocation -> switch (
                invocation.getArgument(0, String.class)) {
            case "SD-COMPLETED-OPEN" -> completedWithOpenLine;
            case "SD-COMPLETED-FULL" -> delivery("SD-COMPLETED-FULL", DocumentStatus.COMPLETED,
                    line(1, "8", "8"));
            case "SD-DRAFT" -> delivery("SD-DRAFT", DocumentStatus.DRAFT, line(1, "3", "0"));
            case "SD-REVERSED" -> delivery("SD-REVERSED", DocumentStatus.REVERSED, line(1, "4", "0"));
            default -> throw new AssertionError("未配置出库单");
        });
    }

    @Test
    void 会计仅凭销售发票权限可查候选_且只返回已过账出库单未开完行() throws Exception {
        mockMvc.perform(get("/api/sales/invoices/delivery-options")
                        .param("page", "1").param("size", "20")
                        .with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].docNo").value("SD-COMPLETED-OPEN"))
                .andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].lines.length()").value(1))
                .andExpect(jsonPath("$.items[0].lines[0].deliveryLineNo").value(1))
                .andExpect(jsonPath("$.items[0].lines[0].invoicedQty").value("4.000000"))
                .andExpect(jsonPath("$.items[0].lines[0].outstandingInvoiceableQty").value("6.000000"));

        ArgumentCaptor<SalesDeliveryQuery> queryCaptor =
                ArgumentCaptor.forClass(SalesDeliveryQuery.class);
        Mockito.verify(salesDeliveryAppService).search(queryCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(queryCaptor.getValue().status())
                .isEqualTo(DocumentStatus.COMPLETED);
        org.assertj.core.api.Assertions.assertThat(queryCaptor.getValue().invoiceableOnly()).isTrue();
    }

    @Test
    void 会计可查单个候选详情_响应仍只含未开完行() throws Exception {
        mockMvc.perform(get("/api/sales/invoices/delivery-options/SD-COMPLETED-OPEN")
                        .with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docNo").value("SD-COMPLETED-OPEN"))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].productId").value(101))
                .andExpect(jsonPath("$.totalCogs").doesNotExist())
                .andExpect(jsonPath("$.lines[0].cogsAmount").doesNotExist());
    }

    @Test
    void 草稿已冲销与已开完出库单均不可经候选详情读取() throws Exception {
        for (String docNo : List.of("SD-DRAFT", "SD-REVERSED", "SD-COMPLETED-FULL")) {
            mockMvc.perform(get("/api/sales/invoices/delivery-options/{docNo}", docNo)
                            .with(asUser(Role.ACCOUNTANT)))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void 无销售发票权限角色查候选403且不调用出库服务() throws Exception {
        Mockito.clearInvocations(salesDeliveryAppService);
        mockMvc.perform(get("/api/sales/invoices/delivery-options")
                        .with(asUser(Role.WAREHOUSE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(salesDeliveryAppService);
    }

    @Test
    void 未登录查候选401() throws Exception {
        mockMvc.perform(get("/api/sales/invoices/delivery-options"))
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

    private static SalesDelivery delivery(String docNo, DocumentStatus status,
                                           SalesDeliveryLine... lines) {
        return SalesDelivery.restore(docNo, "SO-SOURCE", 9L, null, status, List.of(lines), "tester");
    }

    private static SalesDeliveryLine line(int lineNo, String quantity, String invoicedQty) {
        return SalesDeliveryLine.restore(lineNo, lineNo, lineNo, 100L + lineNo,
                new BigDecimal(quantity).setScale(6), new BigDecimal("12.00"),
                new BigDecimal(invoicedQty).setScale(6));
    }
}
