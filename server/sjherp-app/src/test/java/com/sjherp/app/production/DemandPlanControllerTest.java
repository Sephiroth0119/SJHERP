package com.sjherp.app.production;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
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

import com.sjherp.app.config.TransactionalDemandPlanService;
import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.production.DemandPlan;
import com.sjherp.domain.production.DemandPlanLine;
import com.sjherp.domain.production.DemandPlanNotFoundException;

/**
 * DemandPlanController MockMvc 切片测试（M5-T02）。
 *
 * <p>使用 {@code MockMvcBuilders.standaloneSetup}，{@link ProductionExceptionHandler} 通过
 * {@code setControllerAdvice} 接入；{@code @PreAuthorize} 在 standaloneSetup 中不生效，
 * 鉴权场景由 {@link DemandPlanApiPermissionTest} 覆盖。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>创建成功 → 201，字段序列化验证（docNo/planDate/status/lines）；</li>
 *   <li>创建缺必填字段（planDate/lines）→ 400（Bean Validation）；</li>
 *   <li>创建领域规则拒绝 → 400（IllegalArgumentException）；</li>
 *   <li>更新成功 → 200；</li>
 *   <li>按 docNo 查不存在 → 404；</li>
 *   <li>按 docNo 查存在 → 200；</li>
 *   <li>分页查询 → 200（PageResponse 结构）；</li>
 *   <li>分页查询 status 参数非法 → 400。</li>
 * </ul>
 */
class DemandPlanControllerTest {

    private TransactionalDemandPlanService demandPlanService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        demandPlanService = Mockito.mock(TransactionalDemandPlanService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new DemandPlanController(demandPlanService))
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

    // ---------------------------------------------------------------- 辅助

    /**
     * 从 restore 工厂重建需求计划聚合根（不重跑业务校验），构造 stub 返回值。
     */
    private static DemandPlan fakePlan(long id, String docNo, LocalDate planDate) {
        DemandPlanLine line = new DemandPlanLine(100L, new BigDecimal("10.000000"), 1L,
                planDate.plusDays(7));
        Instant now = Instant.now();
        return DemandPlan.restore(
                id, docNo, planDate, ArchiveStatus.ENABLED, "测试备注",
                List.of(line), "alice", now, "alice", now);
    }

    // ================================================================ 1. 创建

    @Test
    void 创建成功_201_字段序列化() throws Exception {
        LocalDate planDate = LocalDate.of(2026, 6, 1);
        DemandPlan saved = fakePlan(1L, "DP-202606-0001", planDate);
        Mockito.when(demandPlanService.create(any(), anyString())).thenReturn(saved);

        mvc.perform(post("/api/production/demand-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "planDate": "2026-06-01",
                                    "remark": "测试备注",
                                    "lines": [
                                        {
                                            "productId": 100,
                                            "quantity": "10.000000",
                                            "unitId": 1,
                                            "dueDate": "2026-06-08"
                                        }
                                    ]
                                }
                                """))
                // 预期 201 Created
                .andExpect(status().isCreated())
                // 基础字段
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.docNo").value("DP-202606-0001"))
                .andExpect(jsonPath("$.planDate").value("2026-06-01"))
                .andExpect(jsonPath("$.status").value("ENABLED"))
                .andExpect(jsonPath("$.remark").value("测试备注"))
                .andExpect(jsonPath("$.createdBy").value("alice"))
                // 行数组
                .andExpect(jsonPath("$.lines").isArray())
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].productId").value(100))
                .andExpect(jsonPath("$.lines[0].unitId").value(1));
    }

    @Test
    void 创建缺planDate_400_BeanValidation() throws Exception {
        // planDate 必填，缺失触发 @NotNull 校验
        mvc.perform(post("/api/production/demand-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "remark": "缺少日期",
                                    "lines": [
                                        {"productId": 100, "quantity": "5", "unitId": 1}
                                    ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        // Bean Validation 在 Service 之前触发，Service 不应被调用
        Mockito.verifyNoInteractions(demandPlanService);
    }

    @Test
    void 创建缺lines_400_BeanValidation() throws Exception {
        // lines 字段必须存在且非空
        mvc.perform(post("/api/production/demand-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "planDate": "2026-06-01"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(demandPlanService);
    }

    @Test
    void 创建lines为空数组_400_BeanValidation() throws Exception {
        // lines 不得为空列表（@NotEmpty）
        mvc.perform(post("/api/production/demand-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "planDate": "2026-06-01",
                                    "lines": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(demandPlanService);
    }

    @Test
    void 创建行数量为0_400_BeanValidation() throws Exception {
        // quantity 须 > 0（@DecimalMin）
        mvc.perform(post("/api/production/demand-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "planDate": "2026-06-01",
                                    "lines": [
                                        {"productId": 100, "quantity": "0", "unitId": 1}
                                    ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(demandPlanService);
    }

    @Test
    void 创建领域规则拒绝_400_IllegalArgumentException() throws Exception {
        // Service 在校验商品时抛出 IllegalArgumentException
        Mockito.when(demandPlanService.create(any(), anyString()))
                .thenThrow(new IllegalArgumentException("商品不存在: id=999"));

        mvc.perform(post("/api/production/demand-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "planDate": "2026-06-01",
                                    "lines": [
                                        {"productId": 999, "quantity": "5", "unitId": 1}
                                    ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("商品不存在")));
    }

    // ================================================================ 2. 更新

    @Test
    void 更新成功_200() throws Exception {
        LocalDate planDate = LocalDate.of(2026, 6, 1);
        DemandPlan updated = fakePlan(1L, "DP-202606-0001", planDate);
        Mockito.when(demandPlanService.update(eq("DP-202606-0001"), any(), anyString()))
                .thenReturn(updated);

        mvc.perform(put("/api/production/demand-plans/DP-202606-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "planDate": "2026-06-01",
                                    "remark": "更新备注",
                                    "lines": [
                                        {"productId": 100, "quantity": "20", "unitId": 1}
                                    ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.docNo").value("DP-202606-0001"));

        // 路径参数 docNo 正确透传给 Service
        Mockito.verify(demandPlanService).update(eq("DP-202606-0001"), any(), eq("alice"));
    }

    @Test
    void 更新不存在_404() throws Exception {
        Mockito.when(demandPlanService.update(eq("DP-999999-9999"), any(), anyString()))
                .thenThrow(DemandPlanNotFoundException.byDocNo("DP-999999-9999"));

        mvc.perform(put("/api/production/demand-plans/DP-999999-9999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "planDate": "2026-06-01",
                                    "lines": [
                                        {"productId": 100, "quantity": "5", "unitId": 1}
                                    ]
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ================================================================ 3. 查询

    @Test
    void 按docNo查不存在_404() throws Exception {
        Mockito.when(demandPlanService.get("DP-999999-9999"))
                .thenThrow(DemandPlanNotFoundException.byDocNo("DP-999999-9999"));

        mvc.perform(get("/api/production/demand-plans/DP-999999-9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void 按docNo查存在_200() throws Exception {
        LocalDate planDate = LocalDate.of(2026, 6, 1);
        DemandPlan plan = fakePlan(5L, "DP-202606-0005", planDate);
        Mockito.when(demandPlanService.get("DP-202606-0005")).thenReturn(plan);

        mvc.perform(get("/api/production/demand-plans/DP-202606-0005"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.docNo").value("DP-202606-0005"))
                .andExpect(jsonPath("$.status").value("ENABLED"));
    }

    @Test
    void 分页查询_200_PageResponse结构() throws Exception {
        LocalDate planDate = LocalDate.of(2026, 6, 1);
        DemandPlan plan1 = fakePlan(1L, "DP-202606-0001", planDate);
        DemandPlan plan2 = fakePlan(2L, "DP-202606-0002", planDate.plusDays(1));
        Mockito.when(demandPlanService.search(any()))
                .thenReturn(new PageResult<>(List.of(plan1, plan2), 2L, 1, 20));

        mvc.perform(get("/api/production/demand-plans?page=1&size=20"))
                .andExpect(status().isOk())
                // PageResponse 标准结构
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].docNo").value("DP-202606-0001"))
                .andExpect(jsonPath("$.items[1].docNo").value("DP-202606-0002"));
    }

    @Test
    void 分页查询status参数非法_400() throws Exception {
        // status=FOO 不合法，Controller.parseStatus 抛 IllegalArgumentException
        mvc.perform(get("/api/production/demand-plans?status=FOO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(demandPlanService);
    }

    @Test
    void 分页查询status合法_200() throws Exception {
        Mockito.when(demandPlanService.search(any()))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 20));

        mvc.perform(get("/api/production/demand-plans?status=ENABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }
}
