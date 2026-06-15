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

import com.sjherp.app.config.TransactionalWorkOrderService;
import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.app.security.PermissionGuard;
import com.sjherp.app.security.SecurityConfig;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.domain.production.WorkOrder;

/**
 * 工单 API 权限测试（M5-T03）：
 *
 * <ul>
 *   <li>ADMIN/BOSS 拥有 {@code production:wo} 权限 → 建单 201；</li>
 *   <li>无权角色（SALES/WAREHOUSE/PURCHASER/ACCOUNTANT）→ 403 统一文案；</li>
 *   <li>未登录 → 401。</li>
 * </ul>
 *
 * <p>参照 {@link MrpApiPermissionTest} 范式：
 * {@code @Import({SecurityConfig.class, PermissionGuard.class})}，
 * 用 {@code authentication()} 直接注入认证态（不走 JWT token 解析）。
 */
@WebMvcTest(controllers = WorkOrderController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class})
class WorkOrderApiPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionalWorkOrderService woService;
    /** JWT 过滤器装配依赖（本测试用 authentication() 直接注入认证态，不走 token 解析） */
    @MockitoBean
    private UserRepository userRepository;

    private static final String CREATE_WO_JSON = """
            {
                "productId": 1,
                "plannedQty": 50,
                "unitId": 1
            }""";

    @BeforeEach
    void setUp() {
        // stub 建单返回值（权限通过后才会被调用）
        WorkOrder stub = WorkOrder.restore(
                1L, "WO-202606-0001", 100L,
                new BigDecimal("50.000000"), 1L,
                BigDecimal.ZERO,
                null, null, null, null,
                com.sjherp.domain.production.WorkOrderSourceType.MANUAL,
                null, null, null,
                DocumentStatus.DRAFT, "tester");
        Mockito.when(woService.createManual(
                        Mockito.anyLong(), Mockito.any(), Mockito.anyLong(),
                        Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyString()))
                .thenReturn(stub);
        Mockito.when(woService.search(Mockito.any()))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 20));
        Mockito.when(woService.get(Mockito.anyString()))
                .thenReturn(stub);
    }

    /** 构造与 JWT 过滤器同构的认证态：principal=AuthenticatedUser，权限=ROLE_角色名 */
    private static RequestPostProcessor asUser(Role... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "tester", "测试用户", Set.of(roles));
        var authorities = Set.of(roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    // ---------------------------------------------------------------- 建单：权限内放行 201

    @Test
    void 管理员建单_201() throws Exception {
        mockMvc.perform(post("/api/production/work-orders").with(asUser(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_WO_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.docNo").value("WO-202606-0001"));
    }

    @Test
    void 老板建单_201() throws Exception {
        mockMvc.perform(post("/api/production/work-orders").with(asUser(Role.BOSS))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_WO_JSON))
                .andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------- 建单：越权 403

    @Test
    void 销售建单_403_统一文案() throws Exception {
        mockMvc.perform(post("/api/production/work-orders").with(asUser(Role.SALES))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_WO_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(woService);
    }

    @Test
    void 仓管建单_403() throws Exception {
        mockMvc.perform(post("/api/production/work-orders").with(asUser(Role.WAREHOUSE))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_WO_JSON))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(woService);
    }

    @Test
    void 采购建单_403() throws Exception {
        mockMvc.perform(post("/api/production/work-orders").with(asUser(Role.PURCHASER))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_WO_JSON))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(woService);
    }

    @Test
    void 会计建单_403() throws Exception {
        mockMvc.perform(post("/api/production/work-orders").with(asUser(Role.ACCOUNTANT))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_WO_JSON))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(woService);
    }

    // ---------------------------------------------------------------- 查询：受控须 production:wo

    @Test
    void 管理员查工单列表_200() throws Exception {
        mockMvc.perform(get("/api/production/work-orders").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void 老板查工单详情_200() throws Exception {
        mockMvc.perform(get("/api/production/work-orders/WO-202606-0001").with(asUser(Role.BOSS)))
                .andExpect(status().isOk());
    }

    @Test
    void 销售查工单列表_403() throws Exception {
        mockMvc.perform(get("/api/production/work-orders").with(asUser(Role.SALES)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 仓管查工单详情_403() throws Exception {
        mockMvc.perform(get("/api/production/work-orders/WO-202606-0001").with(asUser(Role.WAREHOUSE)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 会计查工单列表_403() throws Exception {
        mockMvc.perform(get("/api/production/work-orders").with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- 未登录 401

    @Test
    void 未登录建单_401() throws Exception {
        mockMvc.perform(post("/api/production/work-orders")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_WO_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    @Test
    void 未登录查工单列表_401() throws Exception {
        mockMvc.perform(get("/api/production/work-orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    @Test
    void 未登录查工单详情_401() throws Exception {
        mockMvc.perform(get("/api/production/work-orders/WO-202606-0001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }
}
