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
import com.sjherp.domain.production.ProductionCostSettlement;
import com.sjherp.domain.production.ProductionCostSettlementLine;

/**
 * 月末成本结转单 API 权限测试（M5-T06）。
 *
 * <p>权限点 {@code production:cost}（ADMIN/BOSS/ACCOUNTANT 拥有——成本结转是会计动作 D8；
 * 其他角色无权）。覆盖：
 * <ul>
 *   <li>ADMIN / BOSS / ACCOUNTANT → 建单 201、查询 200、状态流转 200；</li>
 *   <li>SALES / WAREHOUSE / PURCHASER → 403 + 统一文案 "无权限执行该操作"；</li>
 *   <li>未登录 → 401 + 统一文案 "未登录或登录已过期"。</li>
 * </ul>
 *
 * <p>照 {@link ProductionReportApiPermissionTest} 范式（authentication() 直注认证态，
 * 业务服务 MockitoBean 桩）。注意与报工单不同：会计（ACCOUNTANT）对成本结转**有权**。
 */
@WebMvcTest(controllers = ProductionCostSettlementController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class, ProductionExceptionHandler.class})
class ProductionCostSettlementApiPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductionCostSettlementAppService appService;

    /** JWT 过滤器装配依赖（本测试用 authentication() 直接注入认证态，不走 token 解析） */
    @MockitoBean
    private UserRepository userRepository;

    /** 最小有效结转单桩（行各 BigDecimal 字段非空，供 DTO 序列化） */
    private static final ProductionCostSettlement PC_STUB = buildStub();

    private static ProductionCostSettlement buildStub() {
        ProductionCostSettlementLine line = ProductionCostSettlementLine.create(
                1, "WO-202606-0001",
                new BigDecimal("100.00"), new BigDecimal("50.00"), new BigDecimal("30.00"),
                new BigDecimal("5.000000"), new BigDecimal("180.00"),
                new BigDecimal("2.000000"), new BigDecimal("50.00"), new BigDecimal("90.00"),
                new BigDecimal("0.00"));
        return ProductionCostSettlement.restore(
                1L, "PC-202606-0001", "202606", "桩备注",
                DocumentStatus.DRAFT, null, null, List.of(line), "tester", "tester");
    }

    @BeforeEach
    void setUp() {
        Mockito.when(appService.create(
                        Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.anyString()))
                .thenReturn(PC_STUB);
        Mockito.when(appService.approve(Mockito.anyString(), Mockito.anyString())).thenReturn(PC_STUB);
        Mockito.when(appService.post(Mockito.anyString(), Mockito.anyString())).thenReturn(PC_STUB);
        Mockito.when(appService.cancel(Mockito.anyString(), Mockito.anyString())).thenReturn(PC_STUB);
        Mockito.when(appService.get(Mockito.anyString())).thenReturn(PC_STUB);
        Mockito.when(appService.search(Mockito.any()))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 20));
    }

    private static RequestPostProcessor asUser(Role... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "tester", "测试用户", Set.of(roles));
        var authorities = Set.of(roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    // ================================================================ 建单
    private static final String CREATE_JSON = """
            {
                "period": "202606",
                "remark": "月末结转",
                "lines": [{
                    "workOrderDocNo": "WO-202606-0001",
                    "wipQty": "2",
                    "wipCompletionPct": "50"
                }]
            }""";

    @Test
    void 管理员建结转单_201() throws Exception {
        mockMvc.perform(post("/api/production/cost-settlements").with(asUser(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.docNo").value("PC-202606-0001"));
    }

    @Test
    void 老板建结转单_201() throws Exception {
        mockMvc.perform(post("/api/production/cost-settlements").with(asUser(Role.BOSS))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_JSON))
                .andExpect(status().isCreated());
    }

    @Test
    void 会计建结转单_201() throws Exception {
        // 成本结转是会计动作（D8），ACCOUNTANT 有权——与报工单不同
        mockMvc.perform(post("/api/production/cost-settlements").with(asUser(Role.ACCOUNTANT))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_JSON))
                .andExpect(status().isCreated());
    }

    @Test
    void 销售建结转单_403_统一文案() throws Exception {
        mockMvc.perform(post("/api/production/cost-settlements").with(asUser(Role.SALES))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(appService);
    }

    @Test
    void 仓管建结转单_403() throws Exception {
        mockMvc.perform(post("/api/production/cost-settlements").with(asUser(Role.WAREHOUSE))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void 采购建结转单_403() throws Exception {
        mockMvc.perform(post("/api/production/cost-settlements").with(asUser(Role.PURCHASER))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void 未登录建结转单_401() throws Exception {
        mockMvc.perform(post("/api/production/cost-settlements")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    // ================================================================ 查询（含查询受控）
    @Test
    void 会计查结转单列表_200() throws Exception {
        mockMvc.perform(get("/api/production/cost-settlements").with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isOk());
    }

    @Test
    void 销售查结转单列表_403() throws Exception {
        mockMvc.perform(get("/api/production/cost-settlements").with(asUser(Role.SALES)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 未登录查结转单列表_401() throws Exception {
        mockMvc.perform(get("/api/production/cost-settlements"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 管理员查结转单详情_200() throws Exception {
        mockMvc.perform(get("/api/production/cost-settlements/PC-202606-0001").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docNo").value("PC-202606-0001"));
    }

    @Test
    void 采购查结转单详情_403() throws Exception {
        mockMvc.perform(get("/api/production/cost-settlements/PC-202606-0001").with(asUser(Role.PURCHASER)))
                .andExpect(status().isForbidden());
    }

    // ================================================================ 状态流转端点权限
    @Test
    void 会计审核结转单_200() throws Exception {
        mockMvc.perform(post("/api/production/cost-settlements/PC-202606-0001/approve").with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isOk());
    }

    @Test
    void 会计过账结转单_200() throws Exception {
        mockMvc.perform(post("/api/production/cost-settlements/PC-202606-0001/post").with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isOk());
    }

    @Test
    void 仓管过账结转单_403() throws Exception {
        mockMvc.perform(post("/api/production/cost-settlements/PC-202606-0001/post").with(asUser(Role.WAREHOUSE)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 管理员作废结转单_200() throws Exception {
        mockMvc.perform(post("/api/production/cost-settlements/PC-202606-0001/cancel").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void 销售作废结转单_403() throws Exception {
        mockMvc.perform(post("/api/production/cost-settlements/PC-202606-0001/cancel").with(asUser(Role.SALES)))
                .andExpect(status().isForbidden());
    }
}
