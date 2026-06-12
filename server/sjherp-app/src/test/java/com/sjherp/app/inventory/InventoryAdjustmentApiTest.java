package com.sjherp.app.inventory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

import com.sjherp.app.inventory.InventoryQueryDao.BalanceRow;
import com.sjherp.app.inventory.InventoryQueryDao.TransactionRow;
import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.app.security.PermissionGuard;
import com.sjherp.app.security.SecurityConfig;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.domain.inventory.IdempotencyConflictException;
import com.sjherp.domain.inventory.InsufficientStockException;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.StockMovementResult;

/**
 * 库存 API 权限与契约测试（M3-T01c，MockMvc + 真实 SecurityConfig，仿
 * ArchiveWritePermissionApiTest）：
 * <ul>
 *   <li>POST /api/inventory/adjustments 须 inventory:adjust：SALES 403、
 *       WAREHOUSE/ADMIN 放行（与 Agent 工具同一矩阵）；</li>
 *   <li>错误契约：类型非法 400、库存不足 400、幂等键冲突 409、缺必填查询参数 400，
 *       错误体一律 {"error": "..."}；</li>
 *   <li>查询接口登录即可（无权限点）；未登录 401。</li>
 * </ul>
 */
@WebMvcTest(controllers = InventoryController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class})
class InventoryAdjustmentApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InventoryAdjustmentService adjustmentService;
    @MockitoBean
    private InventoryQueryDao queryDao;
    /** JWT 过滤器装配依赖（本测试用 authentication() 直接注入认证态，不走 token 解析） */
    @MockitoBean
    private UserRepository userRepository;

    private static final String OPENING_JSON = """
            {"type":"OPENING","warehouseId":1,"productId":2,"quantity":"100","unitCost":"10.00"}""";
    private static final String COST_ADJUST_JSON = """
            {"type":"COST_ADJUST","warehouseId":1,"productId":2,"adjustAmount":"12.62"}""";

    /** 构造与 JWT 过滤器同构的认证态：principal=AuthenticatedUser，权限=ROLE_角色名 */
    private static RequestPostProcessor asUser(Role... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "tester", "测试用户", Set.of(roles));
        var authorities = Set.of(roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    private static StockMovementResult openingResult() {
        return new StockMovementResult(9L, 1L, 2L, InventoryTxnType.OPENING,
                new BigDecimal("100.000000"), new BigDecimal("10.000000"), new BigDecimal("1000.00"),
                new BigDecimal("100.000000"), new BigDecimal("1000.00"),
                "OPENING", "OP-202606-0001", 1, "OPENING:OP-202606-0001:1");
    }

    // ---------------------------------------------------------------- 越权 403

    @Test
    void 销售调整库存_403_错误体为统一文案() throws Exception {
        mockMvc.perform(post("/api/inventory/adjustments").with(asUser(Role.SALES))
                        .contentType(MediaType.APPLICATION_JSON).content(OPENING_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
        Mockito.verifyNoInteractions(adjustmentService);
    }

    @Test
    void 会计调整库存_403() throws Exception {
        mockMvc.perform(post("/api/inventory/adjustments").with(asUser(Role.ACCOUNTANT))
                        .contentType(MediaType.APPLICATION_JSON).content(COST_ADJUST_JSON))
                .andExpect(status().isForbidden());
        Mockito.verifyNoInteractions(adjustmentService);
    }

    // ---------------------------------------------------------------- 权限内放行

    @Test
    void 仓管期初建账_201_返回过账流水() throws Exception {
        Mockito.when(adjustmentService.opening(Mockito.eq(1L), Mockito.eq(2L),
                        any(BigDecimal.class), any(BigDecimal.class), Mockito.eq("tester")))
                .thenReturn(openingResult());
        mockMvc.perform(post("/api/inventory/adjustments").with(asUser(Role.WAREHOUSE))
                        .contentType(MediaType.APPLICATION_JSON).content(OPENING_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.srcDocNo").value("OP-202606-0001"))
                .andExpect(jsonPath("$.txnType").value("OPENING"))
                // 精度契约：数量/金额一律字符串承载
                .andExpect(jsonPath("$.balanceQuantityAfter").value("100.000000"))
                .andExpect(jsonPath("$.balanceAmountAfter").value("1000.00"));
    }

    @Test
    void 管理员成本调整_201() throws Exception {
        Mockito.when(adjustmentService.costAdjust(Mockito.eq(1L), Mockito.eq(2L),
                        any(BigDecimal.class), Mockito.eq("tester")))
                .thenReturn(new StockMovementResult(10L, 1L, 2L, InventoryTxnType.COST_ADJUST,
                        new BigDecimal("0.000000"), null, new BigDecimal("12.62"),
                        new BigDecimal("100.000000"), new BigDecimal("1012.62"),
                        "COST_ADJUST", "CA-202606-0001", 1, "COST_ADJUST:CA-202606-0001:1"));
        mockMvc.perform(post("/api/inventory/adjustments").with(asUser(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(COST_ADJUST_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.srcDocNo").value("CA-202606-0001"))
                .andExpect(jsonPath("$.unitCost").doesNotExist());
    }

    // ---------------------------------------------------------------- 参数与领域异常契约

    @Test
    void 调整类型非法_400() throws Exception {
        mockMvc.perform(post("/api/inventory/adjustments").with(asUser(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"SALES_OUT","warehouseId":1,"productId":2,"quantity":"1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("仅支持 OPENING / COST_ADJUST")));
        Mockito.verifyNoInteractions(adjustmentService);
    }

    @Test
    void 缺仓库id_400_BeanValidation() throws Exception {
        mockMvc.perform(post("/api/inventory/adjustments").with(asUser(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"OPENING","productId":2,"quantity":"1","unitCost":"1.00"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("仓库 id 不能为空")));
    }

    @Test
    void 库存不足_400_文案含现存量与需求量() throws Exception {
        Mockito.when(adjustmentService.costAdjust(anyLong(), anyLong(), any(), any()))
                .thenThrow(new InsufficientStockException(1L, 2L,
                        new BigDecimal("10.000000"), new BigDecimal("30.000000")));
        mockMvc.perform(post("/api/inventory/adjustments").with(asUser(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(COST_ADJUST_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("库存不足")));
    }

    @Test
    void 幂等键冲突_409() throws Exception {
        Mockito.when(adjustmentService.opening(anyLong(), anyLong(), any(), any(), any()))
                .thenThrow(new IdempotencyConflictException("OPENING:OP-202606-0001:1", "quantity 不一致"));
        mockMvc.perform(post("/api/inventory/adjustments").with(asUser(Role.WAREHOUSE))
                        .contentType(MediaType.APPLICATION_JSON).content(OPENING_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("幂等键冲突")));
    }

    // ---------------------------------------------------------------- 查询接口（登录即可）

    @Test
    void 余额查询_无权限点角色也放行() throws Exception {
        Mockito.when(queryDao.balances(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResult<>(List.of(new BalanceRow(1L, "WH-202606-0001", "一号仓",
                        2L, "SKU-202606-0001", "不锈钢板 304L",
                        new BigDecimal("100.000000"), new BigDecimal("1000.00"))), 1, 1, 20));
        mockMvc.perform(get("/api/inventory/balances").with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].productName").value("不锈钢板 304L"))
                .andExpect(jsonPath("$.items[0].quantity").value("100.000000"))
                // 派生加权单价：1000.00 / 100 = 10.000000（6 位 HALF_UP 现算）
                .andExpect(jsonPath("$.items[0].unitCost").value("10.000000"));
    }

    @Test
    void 流水查询_登录即可_按仓库商品() throws Exception {
        Mockito.when(queryDao.transactions(anyLong(), anyLong(), anyInt(), anyInt()))
                .thenReturn(new PageResult<>(List.of(new TransactionRow(9L, "OPENING",
                        new BigDecimal("100.000000"), new BigDecimal("10.000000"),
                        new BigDecimal("1000.00"), new BigDecimal("100.000000"),
                        new BigDecimal("1000.00"), "OPENING", "OP-202606-0001", 1,
                        "agent:7", Instant.parse("2026-06-12T08:00:00Z"))), 1, 1, 20));
        mockMvc.perform(get("/api/inventory/transactions?warehouseId=1&productId=2")
                        .with(asUser(Role.SALES)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].srcDocNo").value("OP-202606-0001"))
                .andExpect(jsonPath("$.items[0].operator").value("agent:7"));
    }

    @Test
    void 流水查询_缺必填参数_400() throws Exception {
        mockMvc.perform(get("/api/inventory/transactions?warehouseId=1").with(asUser(Role.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("productId")));
    }

    // ---------------------------------------------------------------- 未登录 401

    @Test
    void 未登录写接口_401() throws Exception {
        mockMvc.perform(post("/api/inventory/adjustments")
                        .contentType(MediaType.APPLICATION_JSON).content(OPENING_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    @Test
    void 未登录查询接口_401() throws Exception {
        mockMvc.perform(get("/api/inventory/balances"))
                .andExpect(status().isUnauthorized());
    }
}
