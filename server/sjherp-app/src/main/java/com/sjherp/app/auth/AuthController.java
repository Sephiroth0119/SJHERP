package com.sjherp.app.auth;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.identity.StrongPassword;
import com.sjherp.app.llm.LlmEgressNotice;
import com.sjherp.app.llm.LlmEgressService;
import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.app.security.JwtService;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.RolePermissions;
import com.sjherp.domain.identity.User;
import com.sjherp.domain.identity.UserService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 认证 API（M2-T05）：
 * <ul>
 *   <li>POST /api/auth/login → 200 {"token", "displayName", "roles", "permissions"}；失败 401 {"error"}</li>
 *   <li>GET  /api/auth/me → 200 当前登录用户 + llmEgress 出境说明（须带 Bearer token）</li>
 *   <li>POST /api/auth/password → 200 本人改密（须核对旧密码，新密码过强度策略）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;
    private final LlmEgressService llmEgressService;

    public AuthController(UserService userService, JwtService jwtService, LlmEgressService llmEgressService) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.llmEgressService = llmEgressService;
    }

    /** 登录请求体 */
    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {
    }

    /** 登录响应体（token 为 JWT HS256，有效期见 sjherp.security.jwt-expire-hours，默认 12h） */
    public record LoginResponse(String token, String displayName, List<String> roles, List<String> permissions) {
    }

    /** 本人改密 */
    public record ChangePasswordRequest(
            @NotBlank(message = "原密码不能为空") String oldPassword,
            @NotBlank(message = "新密码不能为空") @StrongPassword String newPassword) {
    }

    /** 当前用户（含 LLM 出境说明，供聊天页展示告知条） */
    public record MeResponse(long userId, String username, String displayName, List<String> roles,
                             List<String> permissions, LlmEgressNotice llmEgress) {
    }

    /** 登录：用户名 + 密码 → JWT（用户名不存在/密码错误统一 401，不泄露细节；停用账号拒绝） */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        User user = userService.authenticate(request.username(), request.password());
        return new LoginResponse(
                jwtService.issueToken(user),
                user.getDisplayName(),
                user.getRoles().stream().map(Role::name).toList(),
                RolePermissions.permissionCodesOf(user.getRoles()));
    }

    /** 当前登录用户（角色/显示名为数据库实时值，非 token 内快照） */
    @GetMapping("/me")
    public MeResponse me() {
        AuthenticatedUser user = CurrentUser.get();
        return new MeResponse(
                user.userId(),
                user.username(),
                user.displayName(),
                user.roles().stream().map(Role::name).toList(),
                RolePermissions.permissionCodesOf(user.roles()),
                llmEgressService.current());
    }

    /** 本人改密：须核对旧密码；新密码走 PasswordPolicy（API 校验注解 + 领域层双守门） */
    @PostMapping("/password")
    public Map<String, String> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(CurrentUser.get().userId(), request.oldPassword(),
                request.newPassword(), CurrentUser.operator());
        return Map.of("status", "changed");
    }
}
