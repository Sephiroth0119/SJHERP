package com.sjherp.app.production;

import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sjherp.app.config.TransactionalWorkOrderService;
import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.production.WorkOrder;
import com.sjherp.domain.production.WorkOrderNotFoundException;
import com.sjherp.domain.production.WorkOrderSourceType;

/**
 * WorkOrderController MockMvc 切片测试（M5-T03）。
 *
 * <p>使用 {@code MockMvcBuilders.standaloneSetup}，{@link ProductionExceptionHandler} 通过
 * {@code setControllerAdvice} 接入；{@code @PreAuthorize} 在 standaloneSetup 中不生效，
 * 鉴权场景由 {@link WorkOrderApiPermissionTest} 覆盖。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>手工建单成功 → 201，字段序列化（docNo/status/sourceType/plannedQty）；</li>
 *   <li>从 MRP 建议建单成功 → 201，sourceType=MRP_SUGGESTION；</li>
 *   <li>建单缺必填字段（productId）→ 400（Bean Validation）；</li>
 *   <li>建单数量为 0 → 400（@DecimalMin）；</li>
 *   <li>建单领域规则拒绝 → 400（IllegalArgumentException）；</li>
 *   <li>下达/开工/完工/作废/冲销 → 200；</li>
 *   <li>状态流转被拒 → 409（IllegalStateTransitionException）；</li>
 *   <li>按单号查存在 → 200；</li>
 *   <li>按单号查不存在 → 404（WorkOrderNotFoundException）；</li>
 *   <li>分页查询 → 200（PageResponse 结构 total/page/size/items）。</li>
 * </ul>
 */
class WorkOrderControllerTest {

    private TransactionalWorkOrderService woService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        woService = Mockito.mock(TransactionalWorkOrderService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new WorkOrderController(woService))
                .setControllerAdvice(new ProductionExceptionHandler())
                .build();
        // standaloneSetup 不走 JWT 过滤器，直接注入认证态供 CurrentUser.operator() 解析
        AuthenticatedUser principal = new AuthenticatedUser(1L, "alice", "爱丽丝", Set.of(Role.ADMIN));
        var token = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 辅助：构造 stub 工单

    private static WorkOrder fakeWorkOrder(long id, String docNo, DocumentStatus status,
                                           WorkOrderSourceType sourceType) {
        return WorkOrder.restore(
                id, docNo, 100L,
                new BigDecimal("50.000000"), 1L,
                BigDecimal.ZERO,
                null, null, null,
                sourceType == WorkOrderSourceType.MRP_SUGGESTION ? "MRP-202606-0001" : null,
                sourceType,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                "测试备注",
                status, "alice");
    }

    private static WorkOrder fakeManualWo(long id, String docNo, DocumentStatus status) {
        return fakeWorkOrder(id, docNo, status, WorkOrderSourceType.MANUAL);
    }

    // ================================================================ 1. 手工建单

    @Test
    void 手工建单成功_201_字段序列化() throws Exception {
        WorkOrder saved = fakeManualWo(1L, "WO-202606-0001", DocumentStatus.DRAFT);
        Mockito.when(woService.createManual(
                        Mockito.anyLong(), Mockito.any(), Mockito.anyLong(),
                        Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.any(), Mockito.any(), anyString()))
                .thenReturn(saved);

        mvc.perform(post("/api/production/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "productId": 100,
                                    "plannedQty": 50,
                                    "unitId": 1,
                                    "remark": "测试备注"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.docNo").value("WO-202606-0001"))
                .andExpect(jsonPath("$.productId").value(100))
                .andExpect(jsonPath("$.plannedQty").value("50.000000"))
                .andExpect(jsonPath("$.completedQty").value("0"))
                .andExpect(jsonPath("$.sourceType").value("MANUAL"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.createdBy").value("alice"));
    }

    @Test
    void 建单缺productId_400_BeanValidation() throws Exception {
        mvc.perform(post("/api/production/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "plannedQty": 50,
                                    "unitId": 1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(woService);
    }

    @Test
    void 建单数量为零_400_DecimalMin() throws Exception {
        mvc.perform(post("/api/production/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "productId": 100,
                                    "plannedQty": 0,
                                    "unitId": 1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(woService);
    }

    @Test
    void 建单领域规则拒绝_400_IllegalArgumentException() throws Exception {
        Mockito.when(woService.createManual(
                        Mockito.anyLong(), Mockito.any(), Mockito.anyLong(),
                        Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.any(), Mockito.any(), anyString()))
                .thenThrow(new IllegalArgumentException("计划数量必须大于 0"));

        mvc.perform(post("/api/production/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "productId": 100,
                                    "plannedQty": 50,
                                    "unitId": 1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("计划数量")));
    }

    // ================================================================ 2. 从 MRP 建议建单

    @Test
    void 从MRP建议建单_201_sourceType为MRP_SUGGESTION() throws Exception {
        WorkOrder saved = fakeWorkOrder(2L, "WO-202606-0002", DocumentStatus.DRAFT,
                WorkOrderSourceType.MRP_SUGGESTION);
        Mockito.when(woService.createFromSuggestion(anyString(), Mockito.anyLong(), anyString()))
                .thenReturn(saved);

        mvc.perform(post("/api/production/work-orders/from-mrp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "mrpRunDocNo": "MRP-202606-0001",
                                    "productId": 100
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.docNo").value("WO-202606-0002"))
                .andExpect(jsonPath("$.sourceType").value("MRP_SUGGESTION"))
                .andExpect(jsonPath("$.mrpRunDocNo").value("MRP-202606-0001"));
    }

    @Test
    void 从MRP建议建单缺mrpRunDocNo_400() throws Exception {
        mvc.perform(post("/api/production/work-orders/from-mrp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "productId": 100
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(woService);
    }

    // ================================================================ 3. 状态流转

    @Test
    void 下达工单_200_状态APPROVED() throws Exception {
        WorkOrder released = fakeManualWo(1L, "WO-202606-0001", DocumentStatus.APPROVED);
        Mockito.when(woService.release(eq("WO-202606-0001"), anyString())).thenReturn(released);

        mvc.perform(post("/api/production/work-orders/WO-202606-0001/release"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void 开工_200_状态EXECUTING() throws Exception {
        WorkOrder executing = fakeManualWo(1L, "WO-202606-0001", DocumentStatus.EXECUTING);
        Mockito.when(woService.start(eq("WO-202606-0001"), anyString())).thenReturn(executing);

        mvc.perform(post("/api/production/work-orders/WO-202606-0001/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTING"));
    }

    @Test
    void 完工_200_状态COMPLETED() throws Exception {
        WorkOrder completed = fakeManualWo(1L, "WO-202606-0001", DocumentStatus.COMPLETED);
        Mockito.when(woService.complete(eq("WO-202606-0001"), anyString())).thenReturn(completed);

        mvc.perform(post("/api/production/work-orders/WO-202606-0001/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void 作废工单_200_状态CANCELLED() throws Exception {
        WorkOrder cancelled = fakeManualWo(1L, "WO-202606-0001", DocumentStatus.CANCELLED);
        Mockito.when(woService.cancel(eq("WO-202606-0001"), anyString())).thenReturn(cancelled);

        mvc.perform(post("/api/production/work-orders/WO-202606-0001/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void 冲销工单_200_状态REVERSED() throws Exception {
        WorkOrder reversed = fakeManualWo(1L, "WO-202606-0001", DocumentStatus.REVERSED);
        Mockito.when(woService.reverse(eq("WO-202606-0001"), anyString())).thenReturn(reversed);

        mvc.perform(post("/api/production/work-orders/WO-202606-0001/reverse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));
    }

    @Test
    void 状态流转被拒_409_IllegalStateTransitionException() throws Exception {
        Mockito.when(woService.reverse(eq("WO-202606-0001"), anyString()))
                .thenThrow(new IllegalStateTransitionException(
                        "WO-202606-0001", DocumentStatus.EXECUTING, DocumentStatus.REVERSED));

        mvc.perform(post("/api/production/work-orders/WO-202606-0001/reverse"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ================================================================ 4. 查询

    @Test
    void 按单号查存在_200_字段序列化() throws Exception {
        WorkOrder wo = fakeManualWo(1L, "WO-202606-0001", DocumentStatus.DRAFT);
        Mockito.when(woService.get("WO-202606-0001")).thenReturn(wo);

        mvc.perform(get("/api/production/work-orders/WO-202606-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.docNo").value("WO-202606-0001"))
                .andExpect(jsonPath("$.productId").value(100))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void 按单号查不存在_404_WorkOrderNotFoundException() throws Exception {
        Mockito.when(woService.get("WO-NOT-EXIST"))
                .thenThrow(new WorkOrderNotFoundException("WO-NOT-EXIST"));

        mvc.perform(get("/api/production/work-orders/WO-NOT-EXIST"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void 分页查询_200_PageResponse结构() throws Exception {
        WorkOrder wo1 = fakeManualWo(1L, "WO-202606-0001", DocumentStatus.DRAFT);
        WorkOrder wo2 = fakeManualWo(2L, "WO-202606-0002", DocumentStatus.APPROVED);
        Mockito.when(woService.search(any()))
                .thenReturn(new PageResult<>(List.of(wo1, wo2), 2L, 1, 20));

        mvc.perform(get("/api/production/work-orders?page=1&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].docNo").value("WO-202606-0001"))
                .andExpect(jsonPath("$.items[1].docNo").value("WO-202606-0002"));
    }

    @Test
    void 分页查询默认参数_200() throws Exception {
        Mockito.when(woService.search(any()))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 20));

        mvc.perform(get("/api/production/work-orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        Mockito.verify(woService).search(any());
    }

    @Test
    void 按productId过滤查询_200() throws Exception {
        WorkOrder wo = fakeManualWo(1L, "WO-202606-0001", DocumentStatus.DRAFT);
        Mockito.when(woService.search(any()))
                .thenReturn(new PageResult<>(List.of(wo), 1L, 1, 20));

        mvc.perform(get("/api/production/work-orders?productId=100&page=1&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(100));
    }
}
