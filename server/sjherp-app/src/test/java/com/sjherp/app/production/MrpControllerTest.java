package com.sjherp.app.production;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
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

import com.sjherp.app.config.TransactionalMrpService;
import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.production.MrpRun;
import com.sjherp.domain.production.MrpRunNotFoundException;
import com.sjherp.domain.production.MrpSuggestion;
import com.sjherp.domain.production.SuggestionType;

/**
 * MrpController MockMvc 切片测试（M5-T02）。
 *
 * <p>使用 {@code MockMvcBuilders.standaloneSetup}，{@link ProductionExceptionHandler} 通过
 * {@code setControllerAdvice} 接入；{@code @PreAuthorize} 在 standaloneSetup 中不生效，
 * 鉴权场景由 {@link MrpApiPermissionTest} 覆盖。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>运行成功 → 201，字段序列化验证（docNo/warehouseId/suggestions）；</li>
 *   <li>运行缺必填字段（warehouseId）→ 400（Bean Validation）；</li>
 *   <li>运行 warehouseId=0 → 400（@Min 校验）；</li>
 *   <li>运行领域规则拒绝 → 400（IllegalArgumentException）；</li>
 *   <li>按 docNo 查不存在 → 404；</li>
 *   <li>按 docNo 查存在 → 200（含 suggestions）；</li>
 *   <li>历史列表分页查询 → 200（PageResponse 结构）；</li>
 *   <li>运行→查询往返：POST 返回 docNo，GET 同 docNo 返回相同记录。</li>
 * </ul>
 */
class MrpControllerTest {

    private TransactionalMrpService mrpService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mrpService = Mockito.mock(TransactionalMrpService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new MrpController(mrpService))
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
     * 从 restore 工厂重建 MRP 运行聚合根（不重跑业务校验），构造 stub 返回值。
     */
    private static MrpRun fakeMrpRun(long id, String docNo, long warehouseId) {
        MrpSuggestion suggestion = new MrpSuggestion(
                SuggestionType.PURCHASE,
                100L,
                0,
                new BigDecimal("50.000000"),
                new BigDecimal("10.000000"),
                new BigDecimal("40.000000"),
                1L);
        return MrpRun.restore(
                id, docNo, Instant.parse("2026-06-01T08:00:00Z"),
                warehouseId, false, true, "测试备注",
                "alice", List.of(suggestion));
    }

    // ================================================================ 1. 运行 MRP

    @Test
    void 运行成功_201_字段序列化() throws Exception {
        MrpRun saved = fakeMrpRun(1L, "MRP-202606-0001", 10L);
        Mockito.when(mrpService.run(any(), anyString())).thenReturn(saved);

        mvc.perform(post("/api/production/mrp/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "warehouseId": 10,
                                    "includeForecast": false,
                                    "includeSalesOrder": true,
                                    "remark": "测试备注"
                                }
                                """))
                // 预期 201 Created
                .andExpect(status().isCreated())
                // 头字段
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.docNo").value("MRP-202606-0001"))
                .andExpect(jsonPath("$.warehouseId").value(10))
                .andExpect(jsonPath("$.includeForecast").value(false))
                .andExpect(jsonPath("$.includeSalesOrder").value(true))
                .andExpect(jsonPath("$.remark").value("测试备注"))
                .andExpect(jsonPath("$.createdBy").value("alice"))
                // 建议行数组
                .andExpect(jsonPath("$.suggestions").isArray())
                .andExpect(jsonPath("$.suggestions.length()").value(1))
                .andExpect(jsonPath("$.suggestions[0].type").value("PURCHASE"))
                .andExpect(jsonPath("$.suggestions[0].productId").value(100))
                .andExpect(jsonPath("$.suggestions[0].level").value(0))
                .andExpect(jsonPath("$.suggestions[0].baseUnitId").value(1));
    }

    @Test
    void 运行缺warehouseId_400_BeanValidation() throws Exception {
        // warehouseId 必填（@NotNull）
        mvc.perform(post("/api/production/mrp/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "includeForecast": false,
                                    "includeSalesOrder": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(mrpService);
    }

    @Test
    void 运行warehouseId为0_400_MinValidation() throws Exception {
        // warehouseId 须 > 0（@Min(1)）
        mvc.perform(post("/api/production/mrp/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "warehouseId": 0,
                                    "includeForecast": false,
                                    "includeSalesOrder": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(mrpService);
    }

    @Test
    void 运行仓库不存在_400_IllegalArgumentException() throws Exception {
        // Service 在检查仓库时抛出 IllegalArgumentException
        Mockito.when(mrpService.run(any(), anyString()))
                .thenThrow(new IllegalArgumentException("仓库不存在: id=999"));

        mvc.perform(post("/api/production/mrp/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "warehouseId": 999,
                                    "includeForecast": false,
                                    "includeSalesOrder": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("仓库不存在")));
    }

    // ================================================================ 2. 按 docNo 查询

    @Test
    void 按docNo查不存在_404() throws Exception {
        Mockito.when(mrpService.get("MRP-999999-9999"))
                .thenThrow(MrpRunNotFoundException.byDocNo("MRP-999999-9999"));

        mvc.perform(get("/api/production/mrp/runs/MRP-999999-9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void 按docNo查存在_200_含suggestions() throws Exception {
        MrpRun run = fakeMrpRun(5L, "MRP-202606-0005", 10L);
        Mockito.when(mrpService.get("MRP-202606-0005")).thenReturn(run);

        mvc.perform(get("/api/production/mrp/runs/MRP-202606-0005"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.docNo").value("MRP-202606-0005"))
                .andExpect(jsonPath("$.warehouseId").value(10))
                .andExpect(jsonPath("$.suggestions").isArray())
                .andExpect(jsonPath("$.suggestions.length()").value(1));
    }

    // ================================================================ 3. 历史列表

    @Test
    void 历史列表分页查询_200_PageResponse结构() throws Exception {
        MrpRun run1 = fakeMrpRun(1L, "MRP-202606-0001", 10L);
        MrpRun run2 = fakeMrpRun(2L, "MRP-202606-0002", 10L);
        Mockito.when(mrpService.searchHistory(eq(1), eq(20)))
                .thenReturn(new PageResult<>(List.of(run1, run2), 2L, 1, 20));

        mvc.perform(get("/api/production/mrp/runs?page=1&size=20"))
                .andExpect(status().isOk())
                // PageResponse 标准结构
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].docNo").value("MRP-202606-0001"))
                .andExpect(jsonPath("$.items[1].docNo").value("MRP-202606-0002"));
    }

    @Test
    void 历史列表默认分页参数() throws Exception {
        Mockito.when(mrpService.searchHistory(eq(1), eq(20)))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 20));

        // 不传 page/size 使用 Controller 默认值 1/20
        mvc.perform(get("/api/production/mrp/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        Mockito.verify(mrpService).searchHistory(1, 20);
    }

    // ================================================================ 4. 运行→查询往返

    @Test
    void 运行后查询同docNo_200() throws Exception {
        MrpRun run = fakeMrpRun(1L, "MRP-202606-0001", 10L);
        // POST 运行返回 run
        Mockito.when(mrpService.run(any(), anyString())).thenReturn(run);
        // GET 查询同一 docNo 也返回 run
        Mockito.when(mrpService.get("MRP-202606-0001")).thenReturn(run);

        // 触发运行
        mvc.perform(post("/api/production/mrp/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId": 10, "includeForecast": false, "includeSalesOrder": true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.docNo").value("MRP-202606-0001"));

        // 按同一 docNo 查询，数据一致
        mvc.perform(get("/api/production/mrp/runs/MRP-202606-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docNo").value("MRP-202606-0001"))
                .andExpect(jsonPath("$.warehouseId").value(10));
    }
}
