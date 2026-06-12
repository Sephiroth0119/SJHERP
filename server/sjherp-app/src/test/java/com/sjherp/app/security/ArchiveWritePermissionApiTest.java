package com.sjherp.app.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Set;

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

import com.sjherp.app.gap.GapController;
import com.sjherp.app.partner.CustomerController;
import com.sjherp.app.partner.SupplierController;
import com.sjherp.app.warehouse.WarehouseController;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.gap.BusinessModule;
import com.sjherp.domain.gap.GapRecord;
import com.sjherp.domain.gap.GapRecordService;
import com.sjherp.domain.gap.GapSeverity;
import com.sjherp.domain.gap.GapStatus;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.domain.partner.Customer;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.domain.partner.SupplierService;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * REST 写接口的权限点 @PreAuthorize 测试（M2-T06，MockMvc + 真实 SecurityConfig）：
 * <ul>
 *   <li>SALES POST /api/partner/suppliers → 403 {"error":"无权限执行该操作"}；</li>
 *   <li>WAREHOUSE POST /api/partner/customers → 403；建仓库（权限内）放行；</li>
 *   <li>SALES 建客户放行（与 Agent 工具同一矩阵）；维护操作须 *:write（SALES PUT 客户 → 403）；</li>
 *   <li>缺口状态流转须 gap:triage（ADMIN/BOSS）；查询接口登录即可，未登录 401。</li>
 * </ul>
 */
@WebMvcTest(controllers = {CustomerController.class, SupplierController.class,
        WarehouseController.class, GapController.class},
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class})
class ArchiveWritePermissionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomerService customerService;
    @MockitoBean
    private SupplierService supplierService;
    @MockitoBean
    private WarehouseService warehouseService;
    @MockitoBean
    private GapRecordService gapRecordService;
    /** JWT 过滤器装配依赖（本测试用 authentication() 直接注入认证态，不走 token 解析） */
    @MockitoBean
    private UserRepository userRepository;

    private static final String CUSTOMER_JSON = """
            {"name":"测试客户","settlementMethod":"MONTHLY"}""";
    private static final String SUPPLIER_JSON = """
            {"name":"测试供应商","settlementMethod":"MONTHLY"}""";
    private static final String WAREHOUSE_JSON = """
            {"name":"测试仓库"}""";

    /** 构造与 JWT 过滤器同构的认证态：principal=AuthenticatedUser，权限=ROLE_角色名 */
    private static RequestPostProcessor asUser(Role... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "tester", "测试用户", Set.of(roles));
        var authorities = Set.of(roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    // ---------------------------------------------------------------- 越权 403

    @Test
    void 销售创建供应商_403_错误体为统一文案() throws Exception {
        mockMvc.perform(post("/api/partner/suppliers").with(asUser(Role.SALES))
                        .contentType(MediaType.APPLICATION_JSON).content(SUPPLIER_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(supplierService);
    }

    @Test
    void 仓管创建客户_403() throws Exception {
        mockMvc.perform(post("/api/partner/customers").with(asUser(Role.WAREHOUSE))
                        .contentType(MediaType.APPLICATION_JSON).content(CUSTOMER_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(customerService);
    }

    @Test
    void 销售更新客户_无partner_write_403() throws Exception {
        mockMvc.perform(put("/api/partner/customers/1").with(asUser(Role.SALES))
                        .contentType(MediaType.APPLICATION_JSON).content(CUSTOMER_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void 会计创建仓库_403() throws Exception {
        mockMvc.perform(post("/api/warehouse/warehouses").with(asUser(Role.ACCOUNTANT))
                        .contentType(MediaType.APPLICATION_JSON).content(WAREHOUSE_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void 销售流转缺口状态_无gap_triage_403() throws Exception {
        mockMvc.perform(post("/api/gaps/1/status").with(asUser(Role.SALES))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"TRIAGED\"}"))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(gapRecordService);
    }

    // ---------------------------------------------------------------- 权限内放行

    @Test
    void 销售创建客户_放行() throws Exception {
        Mockito.when(customerService.create(Mockito.any(), Mockito.eq("tester")))
                .thenReturn(Customer.restore(1L, "CUS-202606-0001", "测试客户", null, null, null, null,
                        SettlementMethod.MONTHLY, null, "CNY", ArchiveStatus.ENABLED,
                        "tester", Instant.now(), "tester", Instant.now()));
        mockMvc.perform(post("/api/partner/customers").with(asUser(Role.SALES))
                        .contentType(MediaType.APPLICATION_JSON).content(CUSTOMER_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CUS-202606-0001"));
    }

    @Test
    void 仓管创建仓库_放行() throws Exception {
        Mockito.when(warehouseService.create(Mockito.any(), Mockito.eq("tester")))
                .thenReturn(Warehouse.restore(1L, "WH-202606-0001", "测试仓库", null, null, false,
                        ArchiveStatus.ENABLED, "tester", Instant.now(), "tester", Instant.now()));
        mockMvc.perform(post("/api/warehouse/warehouses").with(asUser(Role.WAREHOUSE))
                        .contentType(MediaType.APPLICATION_JSON).content(WAREHOUSE_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("WH-202606-0001"));
    }

    @Test
    void 老板流转缺口状态_放行() throws Exception {
        Mockito.when(gapRecordService.transitionStatus(Mockito.eq(1L), Mockito.eq(GapStatus.TRIAGED),
                        Mockito.eq("tester")))
                .thenReturn(GapRecord.restore(1L, "GAP-202606-0001", "session-1", "测试缺口",
                        "场景", "期望", "缺失能力", BusinessModule.GENERAL, GapSeverity.MEDIUM,
                        GapStatus.TRIAGED, "tester", "tester", Instant.now(), "tester", Instant.now()));
        mockMvc.perform(post("/api/gaps/1/status").with(asUser(Role.BOSS))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"TRIAGED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAGED"));
    }

    // ---------------------------------------------------------------- 未登录 401

    @Test
    void 未登录写接口_401() throws Exception {
        mockMvc.perform(post("/api/partner/customers")
                        .contentType(MediaType.APPLICATION_JSON).content(CUSTOMER_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }
}
