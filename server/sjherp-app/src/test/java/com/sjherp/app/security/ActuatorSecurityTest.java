package com.sjherp.app.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sjherp.app.auth.AuthController;
import com.sjherp.app.llm.LlmEgressService;
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.domain.identity.UserService;

/**
 * M8-T04：Prometheus/health 可匿名刮取；env 等敏感端点生产姿态仍 401。
 *
 * <p>WebMvcTest 不装配 actuator handler，permitAll 的路径在无 handler 时是 404 而不是 401。
 */
@WebMvcTest(controllers = AuthController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12"
        })
@Import(SecurityConfig.class)
class ActuatorSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private LlmEgressService llmEgressService;

    @Test
    void 未登录访问prometheus_不是401() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 未登录访问actuatorHealth_不是401() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 未登录访问env_必须401() throws Exception {
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 未登录访问heapdump_必须401() throws Exception {
        mockMvc.perform(get("/actuator/heapdump"))
                .andExpect(status().isUnauthorized());
    }
}
