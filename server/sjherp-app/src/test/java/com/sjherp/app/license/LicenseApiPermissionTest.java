package com.sjherp.app.license;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * 许可状态 API 权限（M8-T08）：{@code license:view} 仅 ADMIN/BOSS；会计/销售 403；未登录 401。
 */
@WebMvcTest(controllers = LicenseController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import({SecurityConfig.class, PermissionGuard.class})
class LicenseApiPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LicenseService licenseService;
    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        Mockito.when(licenseService.status()).thenReturn(new LicenseDtos.StatusResponse(
                "UNCONFIGURED",
                "未配置",
                false,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                "未配置 license，私有部署默认不挡业务（开发/试用宽松模式）"));
    }

    @Test
    void 管理员查询许可状态_200() throws Exception {
        mockMvc.perform(get("/api/license/status").with(asUser(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNCONFIGURED"))
                .andExpect(jsonPath("$.statusLabel").value("未配置"))
                .andExpect(jsonPath("$.blocking").value(false));
    }

    @Test
    void 老板查询许可状态_200() throws Exception {
        mockMvc.perform(get("/api/license/status").with(asUser(Role.BOSS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void 会计查询许可状态_403() throws Exception {
        mockMvc.perform(get("/api/license/status").with(asUser(Role.ACCOUNTANT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("无权限执行该操作"));
    }

    @Test
    void 销售查询许可状态_403() throws Exception {
        mockMvc.perform(get("/api/license/status").with(asUser(Role.SALES)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 未登录查询许可状态_401() throws Exception {
        mockMvc.perform(get("/api/license/status"))
                .andExpect(status().isUnauthorized());
    }

    private static RequestPostProcessor asUser(Role... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "tester", "测试用户", Set.of(roles));
        var authorities = Set.of(roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }
}
