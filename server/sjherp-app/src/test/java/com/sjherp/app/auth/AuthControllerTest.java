package com.sjherp.app.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sjherp.app.security.JwtService;
import com.sjherp.app.security.SecurityConfig;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.identity.AuthenticationFailedException;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.User;
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.domain.identity.UserService;

/**
 * 认证 API 测试（X-2 交叉校验盲区，MockMvc + 真实 SecurityConfig/JWT 过滤器）：
 * <ul>
 *   <li>登录成功 → 200 {token, displayName, roles}，token 可被 JwtService 解析回用户 id；</li>
 *   <li>密码错误 / 停用账号 → 401，文案与领域层统一口径一致；</li>
 *   <li>GET /api/auth/me 未带 token → 401 统一文案；带合法 token → 200 当前用户；
 *       停用用户即便持有效 token 也 401（逐请求从库刷新启停状态）。</li>
 * </ul>
 */
@WebMvcTest(controllers = AuthController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /** SecurityConfig 装配的真实 JwtService（与生产同一 Bean 定义） */
    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private UserService userService;

    /** JWT 过滤器逐请求刷新用户所依赖的仓储 */
    @MockitoBean
    private UserRepository userRepository;

    private static final String LOGIN_JSON = """
            {"username":"alice","password":"Passw0rd1"}""";

    private static User user(ArchiveStatus status) {
        return User.restore(42L, "alice", "爱丽丝", "$2a$10$abcdefghijklmnopqrstuvwxy",
                Set.of(Role.SALES), status, "tester", Instant.now(), "tester", Instant.now());
    }

    // ---------------------------------------------------------------- 登录

    @Test
    void 登录成功_返回token与展示信息() throws Exception {
        Mockito.when(userService.authenticate("alice", "Passw0rd1"))
                .thenReturn(user(ArchiveStatus.ENABLED));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.displayName").value("爱丽丝"))
                .andExpect(jsonPath("$.roles[0]").value("SALES"));
    }

    @Test
    void 密码错误_401_统一文案不泄露登录名是否存在() throws Exception {
        Mockito.when(userService.authenticate(Mockito.anyString(), Mockito.anyString()))
                .thenThrow(new AuthenticationFailedException("用户名或密码错误"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("用户名或密码错误"));
    }

    @Test
    void 停用账号登录_401() throws Exception {
        Mockito.when(userService.authenticate(Mockito.anyString(), Mockito.anyString()))
                .thenThrow(new AuthenticationFailedException("账号已停用，请联系管理员"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("账号已停用，请联系管理员"));
    }

    // ---------------------------------------------------------------- /me

    @Test
    void me未带token_401_统一文案() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }

    @Test
    void me带合法token_200_返回库中实时用户信息() throws Exception {
        User enabled = user(ArchiveStatus.ENABLED);
        Mockito.when(userRepository.findById(42L)).thenReturn(Optional.of(enabled));
        String token = jwtService.issueToken(enabled);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.displayName").value("爱丽丝"))
                .andExpect(jsonPath("$.roles[0]").value("SALES"));
    }

    @Test
    void 停用用户带有效token_仍401_逐请求刷新启停状态() throws Exception {
        User disabled = user(ArchiveStatus.DISABLED);
        Mockito.when(userRepository.findById(42L)).thenReturn(Optional.of(disabled));
        String token = jwtService.issueToken(disabled);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已过期"));
    }
}
