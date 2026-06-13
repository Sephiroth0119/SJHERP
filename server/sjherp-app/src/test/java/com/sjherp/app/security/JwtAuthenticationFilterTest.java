package com.sjherp.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.User;
import com.sjherp.domain.identity.UserRepository;

/**
 * JwtAuthenticationFilter Authorization 头解析单测：
 *
 * <ul>
 *   <li>标准 {@code Bearer <token>} → 认证成功；</li>
 *   <li>「裸 token」（无 Bearer 前缀，knife4j 纯 UI 模式手填头的场景）→ 仍认证成功；</li>
 *   <li>前缀大小写不敏感（{@code bearer }）、首尾空白容忍；</li>
 *   <li>验签失败（jwtService 返回 empty）→ 不认证，SecurityContext 保持匿名。</li>
 * </ul>
 *
 * <p>关键安全前提：本过滤器只负责剥离可选前缀与 strip 空白，token 真伪一律由
 * {@link JwtService#parseUserId} 做密码学全量验签——本测试通过 mock 验证「头格式容忍」
 * 不等于「放松验签」（empty 即不认证）。
 */
class JwtAuthenticationFilterTest {

    private static final long USER_ID = 42L;
    private static final String VALID_TOKEN = "valid.jwt.token";

    private static User enabledUser() {
        return User.restore(USER_ID, "alice", "爱丽丝", "$2a$10$abcdefghijklmnopqrstuvwxy",
                Set.of(Role.SALES), ArchiveStatus.ENABLED,
                "tester", Instant.now(), "tester", Instant.now());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** 构造过滤器：jwtService 对 VALID_TOKEN 返回用户 id，其余返回 empty；userRepository 返回启用用户 */
    private JwtAuthenticationFilter filter() {
        JwtService jwtService = mock(JwtService.class);
        lenient().when(jwtService.parseUserId(VALID_TOKEN)).thenReturn(Optional.of(USER_ID));
        lenient().when(jwtService.parseUserId(any())).thenAnswer(inv ->
                VALID_TOKEN.equals(inv.getArgument(0)) ? Optional.of(USER_ID) : Optional.empty());

        UserRepository userRepository = mock(UserRepository.class);
        lenient().when(userRepository.findById(USER_ID)).thenReturn(Optional.of(enabledUser()));

        // Spring 真实 Environment 不会返回 null（getActiveProfiles() 至少返回空数组）；
        // mock 默认返回 null，此处显式给空数组，使 shouldNotFilter 走「非 dev profile」分支
        Environment environment = mock(Environment.class);
        lenient().when(environment.getActiveProfiles()).thenReturn(new String[0]);
        return new JwtAuthenticationFilter(jwtService, userRepository, environment);
    }

    private static boolean authenticatedAfter(JwtAuthenticationFilter filter, String authorizationHeader)
            throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/inventory/transactions");
        if (authorizationHeader != null) {
            request.addHeader("Authorization", authorizationHeader);
        }
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated();
    }

    @Test
    void 标准Bearer前缀_认证成功() throws Exception {
        assertThat(authenticatedAfter(filter(), "Bearer " + VALID_TOKEN)).isTrue();
    }

    @Test
    void 裸token无前缀_仍认证成功() throws Exception {
        // knife4j 纯 UI 模式：用户在「请求头部」手填 Authorization: <token>（无 Bearer 前缀）
        assertThat(authenticatedAfter(filter(), VALID_TOKEN)).isTrue();
    }

    @Test
    void 小写bearer前缀_大小写不敏感_认证成功() throws Exception {
        assertThat(authenticatedAfter(filter(), "bearer " + VALID_TOKEN)).isTrue();
    }

    @Test
    void 前后空白_strip后认证成功() throws Exception {
        assertThat(authenticatedAfter(filter(), "  Bearer " + VALID_TOKEN + "  ")).isTrue();
    }

    @Test
    void 无效token_验签失败_不认证() throws Exception {
        // 头格式容忍不等于放松验签：jwtService 对非 VALID_TOKEN 返回 empty → 不认证
        assertThat(authenticatedAfter(filter(), "Bearer some.invalid.token")).isFalse();
        assertThat(authenticatedAfter(filter(), "some.invalid.token")).isFalse();
    }

    @Test
    void 无Authorization头_不认证() throws Exception {
        assertThat(authenticatedAfter(filter(), null)).isFalse();
    }

    @Test
    void 空白Authorization头_不认证() throws Exception {
        assertThat(authenticatedAfter(filter(), "   ")).isFalse();
    }
}
