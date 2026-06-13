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
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.domain.identity.UserService;

/**
 * 生产姿态 knife4j/springdoc 路径安全验证（无 local/dev profile）：
 *
 * <ul>
 *   <li>GET /v3/api-docs 未登录时必须返回 401（SecurityConfig 无 dev/local profile 时不放行文档路径）；</li>
 *   <li>GET /doc.html 未登录时必须返回 401；</li>
 *   <li>验证生产姿态下文档路径受到 JWT 保护，不可匿名访问。</li>
 * </ul>
 *
 * <p>注意：本测试不激活 local/dev profile，SpringBoot 也未启用 springdoc（properties 中
 * springdoc.api-docs.enabled=false），所以实际不存在 /v3/api-docs handler；但 SecurityConfig
 * 的 anyRequest().authenticated() 会在 dispatcher 层返回 401，而不是 404。
 */
@WebMvcTest(controllers = AuthController.class,
        properties = {
                "sjherp.security.jwt-secret=test-only-secret-0123456789-0123456789-0123456789",
                "sjherp.security.jwt-expire-hours=12",
                // 生产姿态：关闭 springdoc（模拟无 local/dev profile 时的行为）
                "springdoc.api-docs.enabled=false",
                "knife4j.enable=false"
        })
@Import(SecurityConfig.class)
class Knife4jProductionSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    /** JWT 过滤器装配依赖 */
    @MockitoBean
    private UserRepository userRepository;

    @Test
    void 生产姿态_未登录访问API文档路径_必须返回401() throws Exception {
        // /v3/api-docs 在无 dev/local profile 时不在白名单，anyRequest().authenticated() 拦截返回 401
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 生产姿态_未登录访问doc_html_必须返回401() throws Exception {
        mockMvc.perform(get("/doc.html"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 生产姿态_未登录访问webjars_必须返回401() throws Exception {
        mockMvc.perform(get("/webjars/knife4j/doc.js"))
                .andExpect(status().isUnauthorized());
    }
}
