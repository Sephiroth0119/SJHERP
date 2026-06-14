package com.sjherp.app.production;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
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

import com.sjherp.app.config.TransactionalMrpService;
import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.app.security.PermissionGuard;
import com.sjherp.app.security.SecurityConfig;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.domain.production.MrpRun;
import com.sjherp.domain.production.MrpSuggestion;
import com.sjherp.domain.production.SuggestionType;

/**
 * MRP API 权限测试（M5-T02，MockMvc + 真实 SecurityConfig，照
 * {@code PaymentAccountApiPermissionTest} 范式）：
 * <ul>
 *   <li><b>运行</b>（POST）须 {@code production:mrp}（ADMIN/BOSS 放行）；</li>
 *   <li>无权角色（SALES/WAREHOUSE/PURCHASER/ACCOUNTANT）运行 → 403 统一文案；未登录 → 401；</li>
 *   <li><b>查询</b>（GET 列表 / GET 详情）同样受控须 production:mrp（MRP 建议含经营敏感数据）：
 *       ADMIN/BOSS 200，无权角色 403，未登录 → 401。</li>
 * </ul>
 *
 * <p>装配口径同 {@code DemandPlanApiPermissionTest}：
 * {@code @Import({SecurityConfig.class, PermissionGuard.class})}
 * （{@code @perm.has(...)} 依赖 perm bean），用 {@code authentication()} 直接注入认证态。
 */
@WebMvcTest(controllers = MrpController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class})
class MrpApiPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionalMrpService mrpService;
    /** JWT 过滤器装配依赖（本测试用 authentication() 直接注入认证态，不走 token 解析） */
    @MockitoBean
    private UserRepository userRepository;

    private static final String RUN_JSON = """
            {
                "warehouseId": 10,
                "includeForecast": false,
                "includeSalesOrder": true,
                "remark": "权限测试运行"
            }""";

    @BeforeEach
    void setUp() {
        MrpSuggestion suggestion = new MrpSuggestion(
                SuggestionType.PURCHASE, 100L, 0,
                new BigDecimal("50.000000"), new BigDecimal("10.000000"),
                new BigDecimal("40.000000"), 1L);
        MrpRun saved = MrpRun.restore(
                1L, "MRP-202606-0001", Instant.parse("2026-06-01T08:00:00Z"),
                10L, false, true, "权限测试运行",
                "tester", List.of(suggestion));

        Mockito.when(mrpService.run(Mockito.any(), Mockito.anyString()))
                .thenReturn(saved);
        Mockito.when(mrpService.searchHistory(Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 20));
        Mockito.when(mrpService.get(Mockito.anyString()))
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

    // ---------------------------------------------------------------- 运行：权限内放行 201

    @Test
    void 管理员运行MRP_201() throws Exception {
        mockMvc.perform(post("/api/production/mrp/runs").with(asUser(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(RUN_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.docNo").value("MRP-202606-0001"));
    }

    @Test
    void 老板运行MRP_201() throws Exception {
        mockMvc.perform(post("/api/production/mrp/runs").with(asUser(Role.BOSS))
                        .contentType(MediaType.APPLICATION_JSON).content(RUN_JSON))
                .andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------- 运行：越权 403

    @Test
    void 销售运行MRP_403_统一文案() throws Exception {
        mockMvc.perform(post("/api/production/mrp/runs").with(asUser(Role.SALES))
                        .contentType(MediaType.APPLICATION_JSON).content(RUN_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(mrpService);
    }

    @Test
    void 仓管运行MRP_403() throws Exception {
        mockMvc.perform(post("/api/production/mrp/runs").with(asUser(Role.WAREHOUSE))
                        .contentType(MediaType.APPLICATION_JSON).content(RUN_JSON))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(mrpService);
    }

    @Test
    void 采购运行MRP_403() throws Exception {
        mockMvc.perform(post("/api/production/mrp/runs").with(asUser(Role.PURCHASER))
                        .contentType(MediaType.APPLICATION_JSON).content(RUN_JSON))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(mrpService);
    }

    @Test
    void 会计运行MRP_403() throws Exception {
        mockMvc.perform(post("/api/production/mrp/runs").with(asUser(Role.ACCOUNTANT))
                        .contentType(MediaType.APPLICATION_JSON).content(RUN_JSON))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(mrpService);
    }

    // ---------------------------------------------------------------- 查询：受控须 production:mrp（同 POST 口径）

    @Test
    void 管理员查MRP历史列表_200() throws Exception {
        mockMvc.perform(get("/api/production/mrp/runs").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void 老板查MRP详情_200() throws Exception {
        mockMvc.perform(get("/api/production/mrp/runs/MRP-202606-0001").with(asUser(Role.BOSS)))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- 查询：越权 403（MRP 建议含经营敏感数据，查询同权）

    @Test
    void 销售查MRP历史列表_403() throws Exception {
        mockMvc.perform(get("/api/production/mrp/runs").with(asUser(Role.SALES)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 仓管查MRP详情_403() throws Exception {
        mockMvc.perform(get("/api/production/mrp/runs/MRP-202606-0001").with(asUser(Role.WAREHOUSE)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 会计查MRP历史列表_403() throws Exception {
        mockMvc.perform(get("/api/production/mrp/runs").with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- 未登录 401

    @Test
    void 未登录运行MRP_401() throws Exception {
        mockMvc.perform(post("/api/production/mrp/runs")
                        .contentType(MediaType.APPLICATION_JSON).content(RUN_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    @Test
    void 未登录查MRP历史列表_401() throws Exception {
        mockMvc.perform(get("/api/production/mrp/runs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    @Test
    void 未登录查MRP详情_401() throws Exception {
        mockMvc.perform(get("/api/production/mrp/runs/MRP-202606-0001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }
}
