package com.sjherp.app.production;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import com.sjherp.domain.production.ProductionReport;
import com.sjherp.domain.production.ProductionReportLine;

/**
 * 报工单 API 权限测试（M5-T05）。
 *
 * <p>权限点 {@code production:report}（ADMIN/BOSS 拥有，其他角色无权）。
 * 覆盖：
 * <ul>
 *   <li>ADMIN / BOSS → 建单 201、查询 200；</li>
 *   <li>SALES / WAREHOUSE / PURCHASER / ACCOUNTANT → 403 + 统一文案 "无权限执行该操作"；</li>
 *   <li>未登录 → 401 + 统一文案 "未登录或登录已过期"。</li>
 * </ul>
 *
 * <p>使用 {@code authentication()} 直接注入认证态，不走 JWT token 解析，
 * 与 {@link MaterialIssueApiPermissionTest} 范式保持一致。
 * 业务服务以 {@link MockitoBean} 桩替代，权限通过后返回最小有效响应体。
 */
@WebMvcTest(controllers = ProductionReportController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class, ProductionExceptionHandler.class})
class ProductionReportApiPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductionReportAppService productionReportAppService;

    /** JWT 过滤器装配依赖（本测试用 authentication() 直接注入认证态，不走 token 解析） */
    @MockitoBean
    private UserRepository userRepository;

    /** 最小有效报工单桩（仅需 docNo/status 供 DTO 序列化） */
    private static final ProductionReport PR_STUB = buildPrStub();

    private static ProductionReport buildPrStub() {
        ProductionReportLine line = ProductionReportLine.create(
                1, null, null, null,
                new BigDecimal("1.000000"), null, 1L);
        return ProductionReport.restore(
                1L, "PR-202606-0001", "WO-202606-0001", 1L, 100L,
                new BigDecimal("5.000000"), null, 1L, null, "桩备注",
                DocumentStatus.DRAFT, null, null, List.of(line), "tester", "tester");
    }

    @BeforeEach
    void setUp() {
        // 桩：建单返回 stub
        Mockito.when(productionReportAppService.create(
                        Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong(),
                        Mockito.any(), Mockito.any(), Mockito.anyLong(),
                        Mockito.any(), Mockito.any(), Mockito.anyString()))
                .thenReturn(PR_STUB);
        Mockito.when(productionReportAppService.approve(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(PR_STUB);
        Mockito.when(productionReportAppService.post(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(PR_STUB);
        Mockito.when(productionReportAppService.cancel(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(PR_STUB);
        Mockito.when(productionReportAppService.get(Mockito.anyString()))
                .thenReturn(PR_STUB);
        Mockito.when(productionReportAppService.search(Mockito.any()))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 20));
    }

    /** 构造认证态（同 MaterialIssueApiPermissionTest#asUser） */
    private static RequestPostProcessor asUser(Role... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "tester", "测试用户", Set.of(roles));
        var authorities = Set.of(roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    // ================================================================ 建单

    private static final String CREATE_PR_JSON = """
            {
                "workOrderDocNo": "WO-202606-0001",
                "warehouseId": 1,
                "productId": 100,
                "completedQty": "5",
                "unitId": 1,
                "lines": [{
                    "reportedHours": "2.5",
                    "unitId": 1
                }]
            }""";

    @Test
    void 管理员建报工单_201() throws Exception {
        mockMvc.perform(post("/api/production/reports").with(asUser(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_PR_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.docNo").value("PR-202606-0001"));
    }

    @Test
    void 老板建报工单_201() throws Exception {
        mockMvc.perform(post("/api/production/reports").with(asUser(Role.BOSS))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_PR_JSON))
                .andExpect(status().isCreated());
    }

    @Test
    void 销售建报工单_403_统一文案() throws Exception {
        mockMvc.perform(post("/api/production/reports").with(asUser(Role.SALES))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_PR_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(productionReportAppService);
    }

    @Test
    void 仓管建报工单_403() throws Exception {
        mockMvc.perform(post("/api/production/reports").with(asUser(Role.WAREHOUSE))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_PR_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void 采购建报工单_403() throws Exception {
        mockMvc.perform(post("/api/production/reports").with(asUser(Role.PURCHASER))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_PR_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void 会计建报工单_403() throws Exception {
        mockMvc.perform(post("/api/production/reports").with(asUser(Role.ACCOUNTANT))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_PR_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void 未登录建报工单_401() throws Exception {
        mockMvc.perform(post("/api/production/reports")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_PR_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    // ================================================================ 查询列表

    @Test
    void 管理员查报工单列表_200() throws Exception {
        mockMvc.perform(get("/api/production/reports").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void 老板查报工单列表_200() throws Exception {
        mockMvc.perform(get("/api/production/reports").with(asUser(Role.BOSS)))
                .andExpect(status().isOk());
    }

    @Test
    void 销售查报工单列表_403() throws Exception {
        mockMvc.perform(get("/api/production/reports").with(asUser(Role.SALES)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 会计查报工单列表_403() throws Exception {
        mockMvc.perform(get("/api/production/reports").with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 未登录查报工单列表_401() throws Exception {
        mockMvc.perform(get("/api/production/reports"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    // ================================================================ 查询详情

    @Test
    void 管理员查报工单详情_200() throws Exception {
        mockMvc.perform(get("/api/production/reports/PR-202606-0001").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docNo").value("PR-202606-0001"));
    }

    @Test
    void 老板查报工单详情_200() throws Exception {
        mockMvc.perform(get("/api/production/reports/PR-202606-0001").with(asUser(Role.BOSS)))
                .andExpect(status().isOk());
    }

    @Test
    void 销售查报工单详情_403() throws Exception {
        mockMvc.perform(get("/api/production/reports/PR-202606-0001").with(asUser(Role.SALES)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 未登录查报工单详情_401() throws Exception {
        mockMvc.perform(get("/api/production/reports/PR-202606-0001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    // ================================================================ 状态流转端点权限

    @Test
    void 管理员审核报工单_200() throws Exception {
        mockMvc.perform(post("/api/production/reports/PR-202606-0001/approve").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void 销售审核报工单_403() throws Exception {
        mockMvc.perform(post("/api/production/reports/PR-202606-0001/approve").with(asUser(Role.SALES)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 管理员过账报工单_200() throws Exception {
        mockMvc.perform(post("/api/production/reports/PR-202606-0001/post").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void 仓管过账报工单_403() throws Exception {
        mockMvc.perform(post("/api/production/reports/PR-202606-0001/post").with(asUser(Role.WAREHOUSE)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 管理员作废报工单_200() throws Exception {
        mockMvc.perform(post("/api/production/reports/PR-202606-0001/cancel").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void 采购作废报工单_403() throws Exception {
        mockMvc.perform(post("/api/production/reports/PR-202606-0001/cancel").with(asUser(Role.PURCHASER)))
                .andExpect(status().isForbidden());
    }
}
