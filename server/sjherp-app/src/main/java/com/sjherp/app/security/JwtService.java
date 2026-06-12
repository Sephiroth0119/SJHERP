package com.sjherp.app.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import javax.crypto.SecretKey;

import com.sjherp.domain.identity.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * JWT 签发与校验（HS256，jjwt）。
 *
 * <p>token 只携带身份（sub = 用户 id）与少量展示信息；角色/启停状态
 * 不作为授权依据——{@link JwtAuthenticationFilter} 逐请求从数据库刷新，
 * 保证停用与角色变更立即生效（token 无需吊销机制）。
 */
public class JwtService {

    /** HS256 密钥下限（jjwt 强制：密钥长度须 ≥ 哈希输出 256 位） */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration expire;

    public JwtService(String secret, long expireHours) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT 密钥（sjherp.security.jwt-secret / 环境变量 SJHERP_JWT_SECRET）必须 ≥ 32 字节");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expire = Duration.ofHours(expireHours);
    }

    /** 为已认证用户签发 token（sub = 用户 id，过期时间见配置，默认 12h） */
    public String issueToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expire)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 校验 token 并解析用户 id；签名非法 / 过期 / 格式错误一律返回 empty
     * （由安全入口点统一回 401，不区分失败原因）。
     */
    public Optional<Long> parseUserId(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            return Optional.of(Long.parseLong(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
