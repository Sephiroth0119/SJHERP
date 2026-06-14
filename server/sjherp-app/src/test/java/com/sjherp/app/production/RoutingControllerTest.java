package com.sjherp.app.production;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.production.Routing;
import com.sjherp.app.config.TransactionalRoutingService;
import com.sjherp.domain.production.RoutingNotFoundException;
import com.sjherp.domain.production.RoutingOperation;

/**
 * RoutingController MockMvc 切片测试（M5-T01）。
 *
 * <p>使用 {@code MockMvcBuilders.standaloneSetup}，不加载 Spring 上下文；
 * {@link ProductionExceptionHandler} 通过 {@code setControllerAdvice} 接入。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>创建成功 → 201，字段序列化验证；</li>
 *   <li>创建缺必填字段 → 400（Bean Validation）；</li>
 *   <li>更新成功 → 200；</li>
 *   <li>启用 → 200；</li>
 *   <li>停用 → 200；</li>
 *   <li>按 id 查不存在 → 404；</li>
 *   <li>分页查询 → 200（PageResponse 结构）。</li>
 * </ul>
 *
 * <p>重要：{@code RoutingOperationResponse.from()} 调用 {@code op.costRate().toPlainString()}，
 * 故 fake 工艺路线的工序 {@code costRate} 必须非 null，避免 NPE。
 */
class RoutingControllerTest {

    private TransactionalRoutingService routingService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        routingService = Mockito.mock(TransactionalRoutingService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new RoutingController(routingService))
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
     * 从 restore 工厂重建工艺路线聚合根（不重跑业务校验），构造 stub 返回值。
     *
     * <p>注意：{@code costRate} 必须非 null，否则
     * {@code RoutingOperationResponse.from()} 调用 {@code toPlainString()} 时 NPE。
     * {@code workCenter} 可以为 null（域允许）。
     */
    private static Routing fakeRouting(long id, long productId, int version) {
        RoutingOperation op = new RoutingOperation(
                10,                     // sequenceNo
                "切割",                  // operationName
                new BigDecimal("2.5"),  // standardHours
                null,                   // workCenter（可选）
                new BigDecimal("50"));  // costRate — 必须非 null，否则序列化 NPE
        Instant now = Instant.now();
        return Routing.restore(
                id, productId, version, ArchiveStatus.ENABLED, "测试工艺",
                List.of(op), "alice", now, "alice", now);
    }

    // ================================================================ 1. 创建

    @Test
    void 创建成功_201_字段序列化() throws Exception {
        Routing saved = fakeRouting(1L, 200L, 1);
        Mockito.when(routingService.create(any(), anyString())).thenReturn(saved);

        mvc.perform(post("/api/production/routings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "productId": 200,
                                    "version": 1,
                                    "remark": "测试工艺",
                                    "operations": [
                                        {
                                            "sequenceNo": 10,
                                            "operationName": "切割",
                                            "standardHours": "2.5",
                                            "costRate": "50"
                                        }
                                    ]
                                }
                                """))
                // 预期 201 Created
                .andExpect(status().isCreated())
                // 基础字段
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.productId").value(200))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("ENABLED"))
                .andExpect(jsonPath("$.remark").value("测试工艺"))
                .andExpect(jsonPath("$.createdBy").value("alice"))
                // 工序数组
                .andExpect(jsonPath("$.operations").isArray())
                .andExpect(jsonPath("$.operations.length()").value(1))
                .andExpect(jsonPath("$.operations[0].sequenceNo").value(10))
                .andExpect(jsonPath("$.operations[0].operationName").value("切割"))
                // BigDecimal 以 toPlainString() 形式序列化
                .andExpect(jsonPath("$.operations[0].standardHours").value("2.5"))
                .andExpect(jsonPath("$.operations[0].costRate").value("50"));
    }

    @Test
    void 创建缺productId_400_BeanValidation() throws Exception {
        // productId 必填，缺失触发 @NotNull 校验
        mvc.perform(post("/api/production/routings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "version": 1,
                                    "operations": [
                                        {
                                            "sequenceNo": 10,
                                            "operationName": "切割",
                                            "standardHours": "2.5",
                                            "costRate": "50"
                                        }
                                    ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        // Bean Validation 在 Service 之前触发，Service 不应被调用
        Mockito.verifyNoInteractions(routingService);
    }

    @Test
    void 创建缺operations_400_BeanValidation() throws Exception {
        // operations 必填且不得为空
        mvc.perform(post("/api/production/routings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "productId": 200,
                                    "version": 1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(routingService);
    }

    @Test
    void 创建工序缺sequenceNo_400_BeanValidation() throws Exception {
        // sequenceNo 不能为空
        mvc.perform(post("/api/production/routings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "productId": 200,
                                    "version": 1,
                                    "operations": [
                                        {
                                            "operationName": "切割",
                                            "standardHours": "2.5",
                                            "costRate": "50"
                                        }
                                    ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(routingService);
    }

    // ================================================================ 2. 更新

    @Test
    void 更新成功_200() throws Exception {
        Routing updated = fakeRouting(1L, 200L, 1);
        Mockito.when(routingService.update(eq(1L), any(), anyString())).thenReturn(updated);

        mvc.perform(put("/api/production/routings/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "productId": 200,
                                    "version": 1,
                                    "remark": "更新备注",
                                    "operations": [
                                        {
                                            "sequenceNo": 10,
                                            "operationName": "焊接",
                                            "standardHours": "1.5",
                                            "costRate": "60"
                                        }
                                    ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.productId").value(200));

        // 路径参数 id=1 正确透传给 Service
        Mockito.verify(routingService).update(eq(1L), any(), eq("alice"));
    }

    // ================================================================ 3. 启用 / 停用

    @Test
    void 启用_200() throws Exception {
        Routing enabled = fakeRouting(1L, 200L, 1);
        Mockito.when(routingService.enable(eq(1L), anyString())).thenReturn(enabled);

        mvc.perform(post("/api/production/routings/1/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("ENABLED"));
    }

    @Test
    void 停用_200() throws Exception {
        RoutingOperation op = new RoutingOperation(
                10, "切割", new BigDecimal("2.5"), null, new BigDecimal("50"));
        Instant now = Instant.now();
        Routing disabled = Routing.restore(
                1L, 200L, 1, ArchiveStatus.DISABLED, "备注",
                List.of(op), "alice", now, "alice", now);
        Mockito.when(routingService.disable(eq(1L), anyString())).thenReturn(disabled);

        mvc.perform(post("/api/production/routings/1/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    // ================================================================ 4. 查询

    @Test
    void 按id查不存在_404() throws Exception {
        // Service 抛出 RoutingNotFoundException，Handler 映射为 404
        Mockito.when(routingService.get(99L))
                .thenThrow(RoutingNotFoundException.byId(99L));

        mvc.perform(get("/api/production/routings/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void 按id查存在_200() throws Exception {
        Routing routing = fakeRouting(5L, 200L, 2);
        Mockito.when(routingService.get(5L)).thenReturn(routing);

        mvc.perform(get("/api/production/routings/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("ENABLED"));
    }

    @Test
    void 分页查询_200_PageResponse结构() throws Exception {
        Routing r1 = fakeRouting(1L, 200L, 1);
        Routing r2 = fakeRouting(2L, 200L, 2);
        Mockito.when(routingService.search(any()))
                .thenReturn(new PageResult<>(List.of(r1, r2), 2L, 1, 20));

        mvc.perform(get("/api/production/routings?productId=200&page=1&size=20"))
                .andExpect(status().isOk())
                // PageResponse 标准结构
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.items[1].version").value(2));
    }

    @Test
    void 分页查询status参数非法_400() throws Exception {
        // status=INVALID 不合法，Controller.parseStatus 抛 IllegalArgumentException
        mvc.perform(get("/api/production/routings?status=INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(routingService);
    }
}
