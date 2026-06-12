package com.sjherp.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * JwtService 签发与校验单测（X-2 交叉校验盲区）：
 * <ul>
 *   <li>签发 → 解析：sub=用户 id、username claim 正确；roles 刻意不入 token
 *       （授权逐请求从库刷新，token 只证明身份——设计口径见 JwtService 类注释）；</li>
 *   <li>过期 / 篡改签名 / 错误密钥 / 非法格式一律返回 empty（统一 401，不区分原因）；</li>
 *   <li>密钥短于 32 字节拒绝启动。</li>
 * </ul>
 */
class JwtServiceTest {

    /** ≥32 字节的测试密钥（HS256 下限） */
    private static final String SECRET = "test-only-secret-0123456789-0123456789-0123456789";
    private static final String OTHER_SECRET = "another-secret-9876543210-9876543210-9876543210";

    private static User user(long id) {
        return User.restore(id, "alice", "爱丽丝", "$2a$10$abcdefghijklmnopqrstuvwxy",
                Set.of(Role.SALES, Role.ADMIN), ArchiveStatus.ENABLED,
                "tester", Instant.now(), "tester", Instant.now());
    }

    @Test
    void 签发后解析_用户id与username正确_roles不入token() {
        JwtService service = new JwtService(SECRET, 12);
        String token = service.issueToken(user(42L));

        // 身份解析：sub = 用户 id
        assertThat(service.parseUserId(token)).contains(42L);

        // 直接解析 claims 验证签发内容：username 为展示信息；roles 不在 token 中
        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(token).getPayload();
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("username", String.class)).isEqualTo("alice");
        // 角色/启停状态逐请求从库刷新，不作为 token 内容（停用/改角色立即生效的前提）
        assertThat(claims.containsKey("roles")).isFalse();
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    @Test
    void 过期token_解析拒绝() {
        // 负有效期 → 签出的 token 已过期
        JwtService expired = new JwtService(SECRET, -1);
        String token = expired.issueToken(user(7L));

        assertThat(expired.parseUserId(token)).isEmpty();
    }

    @Test
    void 篡改签名_解析拒绝() {
        JwtService service = new JwtService(SECRET, 12);
        String token = service.issueToken(user(7L));

        // 翻转签名段第一个字符（保持 base64url 字符集内）。
        // 不能改最后一个字符：256 位签名编码为 43 个 base64url 字符（258 位），
        // 末字符低 2 位是填充位——若恰好只翻转填充位，解码后签名不变，校验仍通过（曾偶发翻车）
        int signatureStart = token.lastIndexOf('.') + 1;
        char first = token.charAt(signatureStart);
        String tampered = token.substring(0, signatureStart) + (first == 'a' ? 'b' : 'a')
                + token.substring(signatureStart + 1);

        assertThat(service.parseUserId(tampered)).isEmpty();
    }

    @Test
    void 错误密钥签发的token_解析拒绝() {
        JwtService other = new JwtService(OTHER_SECRET, 12);
        JwtService service = new JwtService(SECRET, 12);

        String foreignToken = other.issueToken(user(7L));

        assertThat(service.parseUserId(foreignToken)).isEmpty();
    }

    @Test
    void 非法格式token_解析拒绝不抛异常() {
        JwtService service = new JwtService(SECRET, 12);

        assertThat(service.parseUserId("not-a-jwt")).isEmpty();
        assertThat(service.parseUserId("")).isEmpty();
    }

    @Test
    void 密钥不足32字节_拒绝构造() {
        assertThatThrownBy(() -> new JwtService("too-short", 12))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 字节");
    }
}
