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
import com.sjherp.domain.production.MaterialIssue;
import com.sjherp.domain.production.MaterialIssueLine;

/**
 * 领料单、退料单、齐套检查 API 权限测试（M5-T04）。
 *
 * <p>三个控制器共用同一权限点 {@code production:material}（ADMIN/BOSS 拥有，其他角色无权）。
 * 覆盖：
 * <ul>
 *   <li>ADMIN / BOSS → 建单 201（或 200）；</li>
 *   <li>SALES / WAREHOUSE / PURCHASER / ACCOUNTANT → 403 + 统一文案 "无权限执行该操作"；</li>
 *   <li>未登录 → 401 + 统一文案 "未登录或登录已过期"。</li>
 * </ul>
 *
 * <p>使用 {@code authentication()} 直接注入认证态，不走 JWT token 解析，
 * 与 {@link WorkOrderApiPermissionTest} 范式保持一致。
 * 业务服务以 {@link MockitoBean} 桩替代，权限通过后返回最小有效响应体。
 */
@WebMvcTest(controllers = {MaterialIssueController.class, MaterialReturnController.class,
        KittingCheckController.class},
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class, ProductionExceptionHandler.class})
class MaterialIssueApiPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MaterialIssueAppService materialIssueAppService;
    @MockitoBean
    private MaterialReturnAppService materialReturnAppService;
    @MockitoBean
    private KittingCheckAppService kittingCheckAppService;
    /** JWT 过滤器装配依赖（本测试用 authentication() 直接注入认证态，不走 token 解析） */
    @MockitoBean
    private UserRepository userRepository;

    /** 最小有效领料单桩（仅需 docNo/status 供 DTO 序列化） */
    private static final MaterialIssue MI_STUB = buildMiStub();

    private static MaterialIssue buildMiStub() {
        MaterialIssueLine line = MaterialIssueLine.create(
                1, 100L, new BigDecimal("10.000000"), new BigDecimal("10.000000"), 1L);
        MaterialIssue mi = MaterialIssue.restore(
                1L, "MI-202606-0001", "WO-202606-0001", 1L, null,
                DocumentStatus.DRAFT, null, null, List.of(line), "tester", "tester");
        return mi;
    }

    @BeforeEach
    void setUp() {
        // 桩：建单/审核/过账/作废 返回最小 stub
        Mockito.when(materialIssueAppService.create(
                        Mockito.anyString(), Mockito.anyLong(),
                        Mockito.any(), Mockito.any(), Mockito.anyString()))
                .thenReturn(MI_STUB);
        Mockito.when(materialIssueAppService.approve(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(MI_STUB);
        Mockito.when(materialIssueAppService.post(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(MI_STUB);
        Mockito.when(materialIssueAppService.cancel(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(MI_STUB);
        Mockito.when(materialIssueAppService.get(Mockito.anyString()))
                .thenReturn(MI_STUB);
        Mockito.when(materialIssueAppService.search(Mockito.any()))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 20));

        // 退料单桩（权限通过即可，不需要真实响应体）
        Mockito.when(materialReturnAppService.get(Mockito.anyString()))
                .thenReturn(com.sjherp.domain.production.MaterialReturn.restore(
                        1L, "MR-202606-0001", "MI-202606-0001", 1L, null,
                        DocumentStatus.DRAFT, null, null,
                        List.of(com.sjherp.domain.production.MaterialReturnLine.create(
                                1, 100L, new BigDecimal("5.000000"), 1L, null)),
                        "tester", "tester"));
        Mockito.when(materialReturnAppService.search(Mockito.any()))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 20));

        // 齐套检查桩
        Mockito.when(kittingCheckAppService.check(Mockito.anyString(), Mockito.anyLong()))
                .thenReturn(new com.sjherp.domain.production.KittingCheck("WO-202606-0001", 1L, true, List.of()));
    }

    /** 构造认证态（同 WorkOrderApiPermissionTest#asUser） */
    private static RequestPostProcessor asUser(Role... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "tester", "测试用户", Set.of(roles));
        var authorities = Set.of(roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    // ================================================================ 领料单建单

    private static final String CREATE_MI_JSON = """
            {
                "workOrderDocNo": "WO-202606-0001",
                "warehouseId": 1,
                "lines": [{
                    "productId": 100,
                    "requiredQty": "10",
                    "quantity": "10",
                    "unitId": 1
                }]
            }""";

    @Test
    void 管理员建领料单_201() throws Exception {
        mockMvc.perform(post("/api/production/material-issues").with(asUser(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_MI_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.docNo").value("MI-202606-0001"));
    }

    @Test
    void 老板建领料单_201() throws Exception {
        mockMvc.perform(post("/api/production/material-issues").with(asUser(Role.BOSS))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_MI_JSON))
                .andExpect(status().isCreated());
    }

    @Test
    void 销售建领料单_403_统一文案() throws Exception {
        mockMvc.perform(post("/api/production/material-issues").with(asUser(Role.SALES))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_MI_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(materialIssueAppService);
    }

    @Test
    void 仓管建领料单_403() throws Exception {
        mockMvc.perform(post("/api/production/material-issues").with(asUser(Role.WAREHOUSE))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_MI_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void 采购建领料单_403() throws Exception {
        mockMvc.perform(post("/api/production/material-issues").with(asUser(Role.PURCHASER))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_MI_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void 会计建领料单_403() throws Exception {
        mockMvc.perform(post("/api/production/material-issues").with(asUser(Role.ACCOUNTANT))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_MI_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void 未登录建领料单_401() throws Exception {
        mockMvc.perform(post("/api/production/material-issues")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_MI_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    // ================================================================ 领料单查询

    @Test
    void 管理员查领料单列表_200() throws Exception {
        mockMvc.perform(get("/api/production/material-issues").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void 老板查领料单详情_200() throws Exception {
        mockMvc.perform(get("/api/production/material-issues/MI-202606-0001").with(asUser(Role.BOSS)))
                .andExpect(status().isOk());
    }

    @Test
    void 销售查领料单列表_403() throws Exception {
        mockMvc.perform(get("/api/production/material-issues").with(asUser(Role.SALES)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 会计查领料单详情_403() throws Exception {
        mockMvc.perform(get("/api/production/material-issues/MI-202606-0001")
                        .with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 未登录查领料单列表_401() throws Exception {
        mockMvc.perform(get("/api/production/material-issues"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    // ================================================================ 退料单（同一权限点 production:material）

    private static final String CREATE_MR_JSON = """
            {
                "materialIssueDocNo": "MI-202606-0001",
                "warehouseId": 1,
                "lines": [{
                    "productId": 100,
                    "quantity": "5",
                    "unitId": 1
                }]
            }""";

    @Test
    void 管理员建退料单_201() throws Exception {
        com.sjherp.domain.production.MaterialReturn mrStub = com.sjherp.domain.production.MaterialReturn
                .restore(1L, "MR-202606-0001", "MI-202606-0001", 1L, null,
                        DocumentStatus.DRAFT, null, null,
                        List.of(com.sjherp.domain.production.MaterialReturnLine.create(
                                1, 100L, new BigDecimal("5.000000"), 1L, null)),
                        "tester", "tester");
        Mockito.when(materialReturnAppService.create(
                        Mockito.anyString(), Mockito.anyLong(),
                        Mockito.any(), Mockito.any(), Mockito.anyString()))
                .thenReturn(mrStub);

        mockMvc.perform(post("/api/production/material-returns").with(asUser(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_MR_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.docNo").value("MR-202606-0001"));
    }

    @Test
    void 销售建退料单_403() throws Exception {
        mockMvc.perform(post("/api/production/material-returns").with(asUser(Role.SALES))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_MR_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(materialReturnAppService);
    }

    @Test
    void 未登录建退料单_401() throws Exception {
        mockMvc.perform(post("/api/production/material-returns")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_MR_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    // ================================================================ 齐套检查（同一权限点 production:material）

    @Test
    void 管理员齐套检查_200() throws Exception {
        mockMvc.perform(get("/api/production/kitting-check")
                        .param("workOrderDocNo", "WO-202606-0001")
                        .param("warehouseId", "1")
                        .with(asUser(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void 销售齐套检查_403() throws Exception {
        mockMvc.perform(get("/api/production/kitting-check")
                        .param("workOrderDocNo", "WO-202606-0001")
                        .param("warehouseId", "1")
                        .with(asUser(Role.SALES)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(kittingCheckAppService);
    }

    @Test
    void 未登录齐套检查_401() throws Exception {
        mockMvc.perform(get("/api/production/kitting-check")
                        .param("workOrderDocNo", "WO-202606-0001")
                        .param("warehouseId", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }
}
