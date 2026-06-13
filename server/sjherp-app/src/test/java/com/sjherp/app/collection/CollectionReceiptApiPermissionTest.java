package com.sjherp.app.collection;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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

import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.app.security.PermissionGuard;
import com.sjherp.app.security.SecurityConfig;
import com.sjherp.domain.collection.CollectionReceipt;
import com.sjherp.domain.collection.CollectionReceiptLine;
import com.sjherp.domain.collection.CollectionReceiptQuery;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;

/**
 * 收款单 API 权限测试（M4-T04b，MockMvc + 真实 SecurityConfig，照 {@code SettlementApiPermissionTest} 范式）。
 *
 * <p>收款单驱动核销，写与查均须 {@code finance:settlement}（ADMIN/BOSS/ACCOUNTANT 放行，控制器类级
 * {@code @PreAuthorize} 统一守门，复用 M4-T03 核销写权限，无新增权限点）：
 * <ul>
 *   <li>建/审核/过账：权限内 → 201/200；无权角色（SALES/WAREHOUSE/PURCHASER）→ 403 统一文案；未登录 → 401；</li>
 *   <li>查询：finance:settlement 放行；无权 → 403；未登录 → 401。</li>
 * </ul>
 *
 * <p>装配口径同 {@code SettlementApiPermissionTest}：{@code @Import({SecurityConfig.class, PermissionGuard.class})}
 * （{@code @perm.has(...)} 依赖 perm bean），用 {@code authentication()} 直接注入认证态。
 */
@WebMvcTest(controllers = CollectionReceiptController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class})
class CollectionReceiptApiPermissionTest {

    private static final String DOC_NO = "RCPT-202606-0001";

    private static final String CREATE_JSON = """
            {"customerId":7,"paymentAccountId":3,
             "lines":[{"receivableId":100,"allocatedAmount":"300.00"}]}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CollectionReceiptAppService collectionReceiptAppService;
    /** JWT 过滤器装配依赖（本测试用 authentication() 直接注入认证态，不走 token 解析） */
    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        Mockito.when(collectionReceiptAppService.create(Mockito.anyLong(), Mockito.anyLong(),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyString()))
                .thenReturn(receipt(DocumentStatus.DRAFT));
        Mockito.when(collectionReceiptAppService.approve(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(receipt(DocumentStatus.APPROVED));
        Mockito.when(collectionReceiptAppService.post(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(receipt(DocumentStatus.COMPLETED));
        Mockito.when(collectionReceiptAppService.get(Mockito.anyString()))
                .thenReturn(receipt(DocumentStatus.COMPLETED));
        Mockito.when(collectionReceiptAppService.search(any(CollectionReceiptQuery.class)))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 20));
    }

    private static CollectionReceipt receipt(DocumentStatus status) {
        List<CollectionReceiptLine> lines = List.of(
                CollectionReceiptLine.restore(11L, 1, 100L, new BigDecimal("300.00")));
        return CollectionReceipt.restore(DOC_NO, 7L, 3L, LocalDate.of(2026, 6, 14), null,
                status, lines, "tester");
    }

    /** 构造与 JWT 过滤器同构的认证态：principal=AuthenticatedUser，权限=ROLE_角色名 */
    private static RequestPostProcessor asUser(Role... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "tester", "测试用户", Set.of(roles));
        var authorities = Set.of(roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    // ---------------------------------------------------------------- 写：权限内放行

    @Test
    void 会计建收款单_201() throws Exception {
        mockMvc.perform(post("/api/collections").with(asUser(Role.ACCOUNTANT))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.docNo").value(DOC_NO));
    }

    @Test
    void 老板建收款单_201() throws Exception {
        mockMvc.perform(post("/api/collections").with(asUser(Role.BOSS))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_JSON))
                .andExpect(status().isCreated());
    }

    @Test
    void 管理员建收款单_201() throws Exception {
        mockMvc.perform(post("/api/collections").with(asUser(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_JSON))
                .andExpect(status().isCreated());
    }

    @Test
    void 会计过账收款单_200() throws Exception {
        mockMvc.perform(post("/api/collections/" + DOC_NO + "/post").with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    // ---------------------------------------------------------------- 写：越权 403

    @Test
    void 销售建收款单_403_统一文案() throws Exception {
        mockMvc.perform(post("/api/collections").with(asUser(Role.SALES))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(collectionReceiptAppService);
    }

    @Test
    void 仓管建收款单_403() throws Exception {
        mockMvc.perform(post("/api/collections").with(asUser(Role.WAREHOUSE))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_JSON))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(collectionReceiptAppService);
    }

    @Test
    void 采购过账收款单_403() throws Exception {
        mockMvc.perform(post("/api/collections/" + DOC_NO + "/post").with(asUser(Role.PURCHASER)))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(collectionReceiptAppService);
    }

    // ---------------------------------------------------------------- 查询：finance:settlement 守门

    @Test
    void 会计查收款单列表_200() throws Exception {
        mockMvc.perform(get("/api/collections").with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void 销售查收款单列表_403() throws Exception {
        mockMvc.perform(get("/api/collections").with(asUser(Role.SALES)))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(collectionReceiptAppService);
    }

    // ---------------------------------------------------------------- 未登录 401

    @Test
    void 未登录建收款单_401() throws Exception {
        mockMvc.perform(post("/api/collections")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    @Test
    void 未登录查收款单列表_401() throws Exception {
        mockMvc.perform(get("/api/collections"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }
}
