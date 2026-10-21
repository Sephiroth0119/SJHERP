package com.sjherp.app.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sjherp.app.auth.AuthController;
import com.sjherp.app.llm.LlmEgressService;
import com.sjherp.domain.identity.AuthenticationFailedException;
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.domain.identity.UserService;

/**
 * 登录限流：同一 IP 超过阈值返回 429，不把请求放到认证服务。
 */
@WebMvcTest(controllers = AuthController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12",
                "sjherp.security.rate-limit.enabled=true",
                "sjherp.security.rate-limit.login-per-minute=2"
        })
@Import(SecurityConfig.class)
class RateLimitFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private LlmEgressService llmEgressService;

    @Test
    void 登录超过每分钟限额返回429() throws Exception {
        Mockito.when(userService.authenticate(Mockito.anyString(), Mockito.anyString()))
                .thenThrow(new AuthenticationFailedException("用户名或密码错误"));
        String body = """
                {"username":"alice","password":"Passw0rd1"}""";
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Forwarded-For", "203.0.113.9"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Forwarded-For", "203.0.113.9"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Forwarded-For", "203.0.113.9"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("请求过于频繁，请稍后再试"));
        Mockito.verify(userService, Mockito.times(2)).authenticate(Mockito.anyString(), Mockito.anyString());
    }
}
