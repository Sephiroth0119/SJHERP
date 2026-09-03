package com.sjherp.app.sales;

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
import com.sjherp.domain.sales.SalesOrder;
import com.sjherp.domain.sales.SalesOrderLine;
import com.sjherp.domain.sales.SalesOrderQuery;

/**
 * T02h 销售出库订单候选窄读投影权限与过滤验收。
 *
 * <p>WAREHOUSE 只有 {@code sales:delivery}、没有 {@code sales:order}，仍必须能选择真实可发货
 * 订单创建出库单；投影不得泄露草稿、已完成或已发完订单，也不得放宽销售订单写权限。
 */
@WebMvcTest(controllers = SalesDeliveryController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class, SalesExceptionHandler.class})
class SalesDeliveryOrderOptionApiPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SalesDeliveryAppService salesDeliveryAppService;
    @MockitoBean
    private SalesOrderAppService salesOrderAppService;
    @MockitoBean
    private UserRepository userRepository;

    private SalesOrder approvedWithOpenLine;
    private SalesOrder executingWithOpenLine;
    private SalesOrder approvedFullyDelivered;
    private SalesOrder draft;
    private SalesOrder completed;

    @BeforeEach
    void setUp() {
        approvedWithOpenLine = order("SO-APPROVED-OPEN", DocumentStatus.APPROVED,
                line(1, "10", "2", "4"), line(2, "5", "3", "5"));
        executingWithOpenLine = order("SO-EXECUTING-OPEN", DocumentStatus.EXECUTING,
                line(1, "8", "1", "3"));
        approvedFullyDelivered = order("SO-APPROVED-FULL", DocumentStatus.APPROVED,
                line(1, "8", "1", "8"));
        draft = order("SO-DRAFT", DocumentStatus.DRAFT, line(1, "3", "1", "0"));
        completed = order("SO-COMPLETED", DocumentStatus.COMPLETED, line(1, "4", "1", "0"));

        Mockito.when(salesOrderAppService.search(any()))
                .thenReturn(new PageResult<>(
                        List.of(approvedWithOpenLine, executingWithOpenLine), 2L, 1, 20));
        Mockito.when(salesOrderAppService.get("SO-APPROVED-OPEN"))
                .thenReturn(approvedWithOpenLine);
        Mockito.when(salesOrderAppService.get("SO-EXECUTING-OPEN"))
                .thenReturn(executingWithOpenLine);
        Mockito.when(salesOrderAppService.get("SO-APPROVED-FULL"))
                .thenReturn(approvedFullyDelivered);
        Mockito.when(salesOrderAppService.get("SO-DRAFT")).thenReturn(draft);
        Mockito.when(salesOrderAppService.get("SO-COMPLETED")).thenReturn(completed);
    }

    @Test
    void 仓管仅凭销售出库权限可查候选_且只返回允许发货订单未发完行() throws Exception {
        mockMvc.perform(get("/api/sales/deliveries/order-options")
                        .param("page", "1").param("size", "20")
                        .with(asUser(Role.WAREHOUSE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].docNo").value("SO-APPROVED-OPEN"))
                .andExpect(jsonPath("$.items[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.items[0].lines.length()").value(1))
                .andExpect(jsonPath("$.items[0].lines[0].soLineNo").value(1))
                .andExpect(jsonPath("$.items[0].lines[0].deliveredQty").value("4.000000"))
                .andExpect(jsonPath("$.items[0].lines[0].remainingQty").value("6.000000"))
                .andExpect(jsonPath("$.items[1].status").value("EXECUTING"));

        ArgumentCaptor<SalesOrderQuery> queryCaptor = ArgumentCaptor.forClass(SalesOrderQuery.class);
        Mockito.verify(salesOrderAppService).search(queryCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(queryCaptor.getValue().status()).isNull();
        org.assertj.core.api.Assertions.assertThat(queryCaptor.getValue().deliverableOnly()).isTrue();
    }

    @Test
    void 仓管可查执行中部分发货候选详情_响应仍只含未发完行() throws Exception {
        mockMvc.perform(get("/api/sales/deliveries/order-options/SO-EXECUTING-OPEN")
                        .with(asUser(Role.WAREHOUSE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docNo").value("SO-EXECUTING-OPEN"))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].productId").value(101))
                .andExpect(jsonPath("$.lines[0].remainingQty").value("5.000000"));
    }

    @Test
    void 草稿已完成与已发完订单均不可经候选详情读取() throws Exception {
        for (String docNo : List.of("SO-DRAFT", "SO-COMPLETED", "SO-APPROVED-FULL")) {
            mockMvc.perform(get("/api/sales/deliveries/order-options/{docNo}", docNo)
                            .with(asUser(Role.WAREHOUSE)))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void 无销售出库权限角色查候选403且不调用订单服务() throws Exception {
        Mockito.clearInvocations(salesOrderAppService);

        mockMvc.perform(get("/api/sales/deliveries/order-options")
                        .with(asUser(Role.PURCHASER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));

        Mockito.verifyNoInteractions(salesOrderAppService);
    }

    @Test
    void 未登录查候选401() throws Exception {
        mockMvc.perform(get("/api/sales/deliveries/order-options"))
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

    private static SalesOrder order(String docNo, DocumentStatus status, SalesOrderLine... lines) {
        return SalesOrder.restore(docNo, 9L, LocalDate.of(2026, 7, 26), null,
                status, List.of(lines), "tester");
    }

    private static SalesOrderLine line(int lineNo, String quantity, String unitPrice,
                                       String deliveredQty) {
        BigDecimal qty = new BigDecimal(quantity).setScale(6);
        BigDecimal price = new BigDecimal(unitPrice).setScale(6);
        return SalesOrderLine.restore(lineNo, lineNo, 100L + lineNo, qty, price,
                qty.multiply(price).setScale(2), new BigDecimal(deliveredQty).setScale(6));
    }
}
