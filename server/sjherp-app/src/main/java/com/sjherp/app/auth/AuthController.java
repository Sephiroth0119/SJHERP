package com.sjherp.app.auth;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.app.security.JwtService;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.User;
import com.sjherp.domain.identity.UserService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 认证 API（M2-T05）：
 * <ul>
 *   <li>POST /api/auth/login → 200 {"token", "displayName", "roles"}；失败 401 {"error"}</li>
 *   <li>GET  /api/auth/me → 200 当前登录用户（须带 Bearer token）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;

    public AuthController(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    /** 登录请求体 */
    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {
    }

    /** 登录响应体（token 为 JWT HS256，有效期见 sjherp.security.jwt-expire-hours，默认 12h） */
    public record LoginResponse(String token, String displayName, List<String> roles) {
    }

    /** 登录：用户名 + 密码 → JWT（用户名不存在/密码错误统一 401，不泄露细节；停用账号拒绝） */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        User user = userService.authenticate(request.username(), request.password());
        return new LoginResponse(
                jwtService.issueToken(user),
                user.getDisplayName(),
                user.getRoles().stream().map(Role::name).toList());
    }

    /** 当前登录用户（角色/显示名为数据库实时值，非 token 内快照） */
    @GetMapping("/me")
    public Map<String, Object> me() {
        AuthenticatedUser user = CurrentUser.get();
        return Map.of(
                "userId", user.userId(),
                "username", user.username(),
                "displayName", user.displayName(),
                "roles", user.roles().stream().map(Role::name).toList());
    }
}
