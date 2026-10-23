package com.sjherp.app.pilot;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.UserRepository;

/**
 * 演示种子 API 权限测试（M8-T07）：{@code pilot:seed} 仅 ADMIN/BOSS；会计/销售/仓管 403；未登录 401。
 */
@WebMvcTest(controllers = DemoSeedController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class})
class DemoSeedApiPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DemoSeedService demoSeedService;
    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        Mockito.when(demoSeedService.listPacks()).thenReturn(List.of(
                new DemoSeedDtos.PackInfo("sunshine", "演示阳光文具", "演示阳光仓")));
        Mockito.when(demoSeedService.seed(Mockito.any(), Mockito.anyString())).thenReturn(sampleResult());
    }

    @Test
    void 管理员列出种子包_200() throws Exception {
        mockMvc.perform(get("/api/pilot/demo-seed/packs").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("sunshine"))
                .andExpect(jsonPath("$[0].companyLabel").value("演示阳光文具"));
    }

    @Test
    void 老板装载种子_200() throws Exception {
        mockMvc.perform(post("/api/pilot/demo-seed").with(asUser(Role.BOSS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pack\":\"sunshine\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyLabel").value("演示阳光文具"))
                .andExpect(jsonPath("$.periodCloseable").value(true))
                .andExpect(jsonPath("$.alreadyLoaded").value(false));
    }

    @Test
    void 管理员装载种子_200() throws Exception {
        mockMvc.perform(post("/api/pilot/demo-seed").with(asUser(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void 会计装载种子_403_统一文案() throws Exception {
        mockMvc.perform(post("/api/pilot/demo-seed").with(asUser(Role.ACCOUNTANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pack\":\"sunshine\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
    }

    @Test
    void 销售列出种子包_403() throws Exception {
        mockMvc.perform(get("/api/pilot/demo-seed/packs").with(asUser(Role.SALES)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
    }

    @Test
    void 仓管装载种子_403() throws Exception {
        mockMvc.perform(post("/api/pilot/demo-seed").with(asUser(Role.WAREHOUSE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 未登录装载种子_401() throws Exception {
        mockMvc.perform(post("/api/pilot/demo-seed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private static RequestPostProcessor asUser(Role... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "tester", "测试用户", Set.of(roles));
        var authorities = Set.of(roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    private static DemoSeedResult sampleResult() {
        return new DemoSeedResult("sunshine", "演示阳光文具", false, "202610", true, true, List.of(),
                "演示阳光仓", "演示中性笔", "演示草稿纸", "演示晨光供应商", "演示文具零售客户",
                "演示工行基本户", "PO-1", "PR-1", "PINV-1", "PAYV-1", "SO-1", "SD-1", "SINV-1",
                "RCPT-1", "GAP-1", "已装入演示阳光文具");
    }
}
