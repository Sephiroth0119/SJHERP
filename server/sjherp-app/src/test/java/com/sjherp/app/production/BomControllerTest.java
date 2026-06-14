package com.sjherp.app.production;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.sjherp.app.config.TransactionalBomService;
import com.sjherp.domain.production.BillOfMaterials;
import com.sjherp.domain.production.BillOfMaterialsNotFoundException;
import com.sjherp.domain.production.BomCycleException;
import com.sjherp.domain.production.BomExplosion;
import com.sjherp.domain.production.BomExplosionNode;
import com.sjherp.domain.production.BomLine;

/**
 * BomController MockMvc 切片测试（M5-T01）。
 *
 * <p>使用 {@code MockMvcBuilders.standaloneSetup}，{@link ProductionExceptionHandler} 通过
 * {@code setControllerAdvice} 接入；{@code @PreAuthorize} 在 standaloneSetup 中不生效，
 * 鉴权场景由专项权限测试覆盖。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>创建成功 → 201，字段序列化验证；</li>
 *   <li>创建缺必填字段 → 400（Bean Validation）；</li>
 *   <li>创建父件不存在 → 400（领域服务拒绝）；</li>
 *   <li>创建环形依赖 → 400（BomCycleException）；</li>
 *   <li>更新成功 → 200；</li>
 *   <li>启用/停用 → 200；</li>
 *   <li>按 id 查不存在 → 404；</li>
 *   <li>分页查询 → 200（PageResponse 结构）；</li>
 *   <li>BOM 展开 → 200（BomExplosionResponse 结构）。</li>
 * </ul>
 */
class BomControllerTest {

    private TransactionalBomService bomService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        bomService = Mockito.mock(TransactionalBomService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new BomController(bomService))
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
     * 从 restore 工厂重建 BOM 聚合根（不重跑业务校验），构造 stub 返回值。
     * BomLine 子件 id 固定为 2（与父件 productId=1 不同，满足聚合根校验）。
     */
    private static BillOfMaterials fakeBom(long id, long productId, int version) {
        BomLine line = new BomLine(2L, new BigDecimal("5"), BigDecimal.ZERO, 1L);
        Instant now = Instant.now();
        return BillOfMaterials.restore(
                id, productId, version, ArchiveStatus.ENABLED, "测试备注",
                List.of(line), "alice", now, "alice", now);
    }

    /**
     * 构建 BOM 展开结果（根 productId=1，展开量=10，一个直接子件 productId=2）。
     */
    private static BomExplosion fakeExplosion(long rootProductId, BigDecimal rootQty) {
        BomExplosionNode childNode = new BomExplosionNode(
                2L, new BigDecimal("50"), 1L, 1, List.of());
        return new BomExplosion(rootProductId, rootQty, List.of(childNode));
    }

    // ================================================================ 1. 创建

    @Test
    void 创建成功_201_字段序列化() throws Exception {
        // Service 层桩返回已赋 id 的 BOM 聚合根
        BillOfMaterials saved = fakeBom(1L, 100L, 1);
        Mockito.when(bomService.create(any(), anyString())).thenReturn(saved);

        mvc.perform(post("/api/production/boms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "productId": 100,
                                    "version": 1,
                                    "remark": "测试备注",
                                    "lines": [
                                        {
                                            "childProductId": 2,
                                            "quantity": "5.000000",
                                            "scrapRate": "0.000000",
                                            "unitId": 1
                                        }
                                    ]
                                }
                                """))
                // 预期 201 Created
                .andExpect(status().isCreated())
                // 基础字段
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.productId").value(100))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("ENABLED"))
                .andExpect(jsonPath("$.remark").value("测试备注"))
                .andExpect(jsonPath("$.createdBy").value("alice"))
                // BOM 行数组
                .andExpect(jsonPath("$.lines").isArray())
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].childProductId").value(2))
                .andExpect(jsonPath("$.lines[0].unitId").value(1));
    }

    @Test
    void 创建缺productId_400_BeanValidation() throws Exception {
        // productId 必填，缺失触发 @NotNull 校验
        mvc.perform(post("/api/production/boms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "version": 1,
                                    "lines": [
                                        {"childProductId": 2, "quantity": "5", "unitId": 1}
                                    ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        // Bean Validation 在 Service 之前触发，Service 不应被调用
        Mockito.verifyNoInteractions(bomService);
    }

    @Test
    void 创建缺lines_400_BeanValidation() throws Exception {
        // lines 字段必须存在且非空
        mvc.perform(post("/api/production/boms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "productId": 100,
                                    "version": 1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(bomService);
    }

    @Test
    void 创建父件不存在_400_领域服务拒绝() throws Exception {
        // Service 在校验父件商品时抛出 IllegalArgumentException
        Mockito.when(bomService.create(any(), anyString()))
                .thenThrow(new IllegalArgumentException("父件商品不存在: id=999"));

        mvc.perform(post("/api/production/boms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "productId": 999,
                                    "version": 1,
                                    "lines": [
                                        {"childProductId": 2, "quantity": "5", "unitId": 1}
                                    ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("父件商品不存在")));
    }

    @Test
    void 创建环形依赖_400_BomCycleException() throws Exception {
        // 子件与父件形成环时 Service 抛出 BomCycleException
        Mockito.when(bomService.create(any(), anyString()))
                .thenThrow(new BomCycleException("创建 BOM 会形成环形依赖: productId=100 已在子件 2 的 BOM 树中"));

        mvc.perform(post("/api/production/boms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "productId": 100,
                                    "version": 1,
                                    "lines": [
                                        {"childProductId": 2, "quantity": "5", "unitId": 1}
                                    ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                // 错误体必须包含 "error" 字段，内容含"环"关键字
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("环")));
    }

    // ================================================================ 2. 更新

    @Test
    void 更新成功_200() throws Exception {
        BillOfMaterials updated = fakeBom(1L, 100L, 1);
        Mockito.when(bomService.update(eq(1L), any(), anyString())).thenReturn(updated);

        mvc.perform(put("/api/production/boms/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "productId": 100,
                                    "version": 1,
                                    "remark": "更新备注",
                                    "lines": [
                                        {"childProductId": 2, "quantity": "3", "unitId": 1}
                                    ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.productId").value(100));

        // 路径参数 id=1 正确透传给 Service
        Mockito.verify(bomService).update(eq(1L), any(), eq("alice"));
    }

    // ================================================================ 3. 启用 / 停用

    @Test
    void 启用_200() throws Exception {
        BillOfMaterials enabled = fakeBom(1L, 100L, 1);
        Mockito.when(bomService.enable(eq(1L), anyString())).thenReturn(enabled);

        mvc.perform(post("/api/production/boms/1/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("ENABLED"));
    }

    @Test
    void 停用_200() throws Exception {
        // 构建已停用状态的 BOM
        BomLine line = new BomLine(2L, new BigDecimal("5"), BigDecimal.ZERO, 1L);
        Instant now = Instant.now();
        BillOfMaterials disabled = BillOfMaterials.restore(
                1L, 100L, 1, ArchiveStatus.DISABLED, "备注",
                List.of(line), "alice", now, "alice", now);
        Mockito.when(bomService.disable(eq(1L), anyString())).thenReturn(disabled);

        mvc.perform(post("/api/production/boms/1/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    // ================================================================ 4. 查询

    @Test
    void 按id查不存在_404() throws Exception {
        // Service 抛出 BillOfMaterialsNotFoundException，Handler 映射为 404
        Mockito.when(bomService.get(99L))
                .thenThrow(BillOfMaterialsNotFoundException.byId(99L));

        mvc.perform(get("/api/production/boms/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void 按id查存在_200() throws Exception {
        BillOfMaterials bom = fakeBom(5L, 100L, 2);
        Mockito.when(bomService.get(5L)).thenReturn(bom);

        mvc.perform(get("/api/production/boms/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("ENABLED"));
    }

    @Test
    void 分页查询_200_PageResponse结构() throws Exception {
        // 返回含两条 BOM 的分页结果
        BillOfMaterials bom1 = fakeBom(1L, 100L, 1);
        BillOfMaterials bom2 = fakeBom(2L, 100L, 2);
        Mockito.when(bomService.search(any()))
                .thenReturn(new PageResult<>(List.of(bom1, bom2), 2L, 1, 20));

        mvc.perform(get("/api/production/boms?productId=100&page=1&size=20"))
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
        // status=FOO 不合法，Controller.parseStatus 抛 IllegalArgumentException
        mvc.perform(get("/api/production/boms?status=FOO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        Mockito.verifyNoInteractions(bomService);
    }

    // ================================================================ 5. BOM 展开

    @Test
    void 展开_200_BomExplosionResponse结构() throws Exception {
        BigDecimal qty = new BigDecimal("10");
        BomExplosion explosion = fakeExplosion(100L, qty);
        Mockito.when(bomService.explode(eq(100L), any())).thenReturn(explosion);

        mvc.perform(get("/api/production/boms/100/explode?quantity=10"))
                .andExpect(status().isOk())
                // 根节点信息
                .andExpect(jsonPath("$.rootProductId").value(100))
                .andExpect(jsonPath("$.rootQuantity").value("10"))
                // 直接子件节点
                .andExpect(jsonPath("$.nodes").isArray())
                .andExpect(jsonPath("$.nodes.length()").value(1))
                .andExpect(jsonPath("$.nodes[0].productId").value(2))
                .andExpect(jsonPath("$.nodes[0].level").value(1))
                .andExpect(jsonPath("$.nodes[0].unitId").value(1))
                .andExpect(jsonPath("$.nodes[0].children").isArray());
    }

    @Test
    void 展开数量非法_400() throws Exception {
        // quantity=0 时 Service 抛 IllegalArgumentException
        Mockito.when(bomService.explode(anyLong(), any()))
                .thenThrow(new IllegalArgumentException("展开数量必须大于 0: 0"));

        mvc.perform(get("/api/production/boms/100/explode?quantity=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }
}
