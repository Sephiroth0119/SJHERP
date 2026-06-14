package com.sjherp.app.production;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
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

import com.sjherp.app.config.TransactionalDemandPlanService;
import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.app.security.PermissionGuard;
import com.sjherp.app.security.SecurityConfig;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.domain.production.DemandPlan;
import com.sjherp.domain.production.DemandPlanLine;

/**
 * 需求计划 API 权限测试（M5-T02，MockMvc + 真实 SecurityConfig，照
 * {@code PaymentAccountApiPermissionTest} 范式）：
 * <ul>
 *   <li><b>写</b>（建/更新）须 {@code production:plan}（ADMIN/BOSS 放行）；</li>
 *   <li>无权角色（SALES/WAREHOUSE/PURCHASER/ACCOUNTANT）写 → 403 统一文案；未登录写 → 401；</li>
 *   <li><b>查询</b>登录即可（任意角色 200），未登录查 → 401。</li>
 * </ul>
 *
 * <p>装配口径同 {@code PaymentAccountApiPermissionTest}：
 * {@code @Import({SecurityConfig.class, PermissionGuard.class})}
 * （{@code @perm.has(...)} 依赖 perm bean），用 {@code authentication()} 直接注入认证态。
 */
@WebMvcTest(controllers = DemandPlanController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class})
class DemandPlanApiPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionalDemandPlanService demandPlanService;
    /** JWT 过滤器装配依赖（本测试用 authentication() 直接注入认证态，不走 token 解析） */
    @MockitoBean
    private UserRepository userRepository;

    private static final String PLAN_JSON = """
            {
                "planDate": "2026-06-01",
                "remark": "权限测试计划",
                "lines": [
                    {"productId": 100, "quantity": "10.000000", "unitId": 1}
                ]
            }""";

    @BeforeEach
    void setUp() {
        LocalDate planDate = LocalDate.of(2026, 6, 1);
        DemandPlanLine line = new DemandPlanLine(100L, new BigDecimal("10.000000"), 1L, null);
        Instant now = Instant.now();
        DemandPlan saved = DemandPlan.restore(
                1L, "DP-202606-0001", planDate, ArchiveStatus.ENABLED, "权限测试计划",
                List.of(line), "tester", now, "tester", now);
        Mockito.when(demandPlanService.create(Mockito.any(), Mockito.anyString()))
                .thenReturn(saved);
        Mockito.when(demandPlanService.update(Mockito.anyString(), Mockito.any(), Mockito.anyString()))
                .thenReturn(saved);
        Mockito.when(demandPlanService.search(Mockito.any()))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 20));
        Mockito.when(demandPlanService.get(Mockito.anyString()))
                .thenReturn(saved);
    }

    /** 构造与 JWT 过滤器同构的认证态：principal=AuthenticatedUser，权限=ROLE_角色名 */
    private static RequestPostProcessor asUser(Role... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "tester", "测试用户", Set.of(roles));
        var authorities = Set.of(roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    // ---------------------------------------------------------------- 写：权限内放行 201

    @Test
    void 管理员创建需求计划_201() throws Exception {
        mockMvc.perform(post("/api/production/demand-plans").with(asUser(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(PLAN_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.docNo").value("DP-202606-0001"));
    }

    @Test
    void 老板创建需求计划_201() throws Exception {
        mockMvc.perform(post("/api/production/demand-plans").with(asUser(Role.BOSS))
                        .contentType(MediaType.APPLICATION_JSON).content(PLAN_JSON))
                .andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------- 写：越权 403

    @Test
    void 销售创建需求计划_403_统一文案() throws Exception {
        mockMvc.perform(post("/api/production/demand-plans").with(asUser(Role.SALES))
                        .contentType(MediaType.APPLICATION_JSON).content(PLAN_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(demandPlanService);
    }

    @Test
    void 仓管创建需求计划_403() throws Exception {
        mockMvc.perform(post("/api/production/demand-plans").with(asUser(Role.WAREHOUSE))
                        .contentType(MediaType.APPLICATION_JSON).content(PLAN_JSON))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(demandPlanService);
    }

    @Test
    void 采购创建需求计划_403() throws Exception {
        mockMvc.perform(post("/api/production/demand-plans").with(asUser(Role.PURCHASER))
                        .contentType(MediaType.APPLICATION_JSON).content(PLAN_JSON))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(demandPlanService);
    }

    @Test
    void 会计创建需求计划_403() throws Exception {
        mockMvc.perform(post("/api/production/demand-plans").with(asUser(Role.ACCOUNTANT))
                        .contentType(MediaType.APPLICATION_JSON).content(PLAN_JSON))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(demandPlanService);
    }

    @Test
    void 销售更新需求计划_403() throws Exception {
        mockMvc.perform(put("/api/production/demand-plans/DP-202606-0001").with(asUser(Role.SALES))
                        .contentType(MediaType.APPLICATION_JSON).content(PLAN_JSON))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(demandPlanService);
    }

    // ---------------------------------------------------------------- 查询：登录即可

    @Test
    void 销售查需求计划列表_200_登录即可() throws Exception {
        mockMvc.perform(get("/api/production/demand-plans").with(asUser(Role.SALES)))
                .andExpect(status().isOk());
    }

    @Test
    void 仓管查需求计划列表_200_登录即可() throws Exception {
        mockMvc.perform(get("/api/production/demand-plans").with(asUser(Role.WAREHOUSE)))
                .andExpect(status().isOk());
    }

    @Test
    void 采购查需求计划详情_200_登录即可() throws Exception {
        mockMvc.perform(get("/api/production/demand-plans/DP-202606-0001").with(asUser(Role.PURCHASER)))
                .andExpect(status().isOk());
    }

    @Test
    void 会计查需求计划列表_200_登录即可() throws Exception {
        mockMvc.perform(get("/api/production/demand-plans").with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- 未登录 401

    @Test
    void 未登录创建需求计划_401() throws Exception {
        mockMvc.perform(post("/api/production/demand-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(PLAN_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    @Test
    void 未登录查需求计划列表_401() throws Exception {
        mockMvc.perform(get("/api/production/demand-plans"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    @Test
    void 未登录查需求计划详情_401() throws Exception {
        mockMvc.perform(get("/api/production/demand-plans/DP-202606-0001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }
}
