package com.sjherp.app.identity;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.User;
import com.sjherp.domain.identity.UserService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 用户管理 API（M2-T05，整体限定 ADMIN 角色，非 ADMIN 一律 403）：
 * <ul>
 *   <li>GET  /api/identity/users → 200 用户列表（小企业量级，不分页）</li>
 *   <li>POST /api/identity/users → 201 新建用户</li>
 *   <li>PUT  /api/identity/users/{id}/roles → 200 整体替换角色集合</li>
 *   <li>POST /api/identity/users/{id}/enable|disable → 200 启用/停用</li>
 *   <li>POST /api/identity/users/{id}/password → 200 管理员重置密码</li>
 * </ul>
 * 用户不可物理删除（审计追溯），离职即停用。操作人取登录态（CurrentUser）。
 */
@RestController
@RequestMapping("/api/identity/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {

    private final UserService userService;

    public UserAdminController(UserService userService) {
        this.userService = userService;
    }

    // ---------------------------------------------------------------- DTO

    /** 新建用户请求体（roles 为角色名数组，如 ["SALES","WAREHOUSE"]） */
    public record CreateUserRequest(
            @NotBlank(message = "登录名不能为空") String username,
            @NotBlank(message = "显示名不能为空") String displayName,
            @NotBlank(message = "密码不能为空") String password,
            @NotEmpty(message = "至少要分配一个角色") List<String> roles) {
    }

    /** 整体替换角色请求体 */
    public record AssignRolesRequest(
            @NotEmpty(message = "至少要分配一个角色") List<String> roles) {
    }

    /** 管理员重置密码请求体（无需旧密码） */
    public record ResetPasswordRequest(
            @NotBlank(message = "新密码不能为空") String password) {
    }

    /** 用户响应体（绝不返回 passwordHash） */
    public record UserResponse(long id, String username, String displayName, List<String> roles,
                               String status, String createdBy, Instant createdAt,
                               String updatedBy, Instant updatedAt) {

        static UserResponse from(User user) {
            return new UserResponse(user.getId(), user.getUsername(), user.getDisplayName(),
                    user.getRoles().stream().map(Role::name).sorted().toList(),
                    user.getStatus().name(), user.getCreatedBy(), user.getCreatedAt(),
                    user.getUpdatedBy(), user.getUpdatedAt());
        }
    }

    // ---------------------------------------------------------------- 端点

    /** 用户列表（按 id 升序） */
    @GetMapping
    public List<UserResponse> list() {
        return userService.list().stream().map(UserResponse::from).toList();
    }

    /** 新建用户（登录名唯一；密码 ≥8 位且含字母数字，否则 400） */
    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        User user = userService.create(request.username(), request.displayName(),
                request.password(), parseRoles(request.roles()), CurrentUser.operator());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    /** 整体替换角色集合（至少一个角色） */
    @PutMapping("/{id}/roles")
    public UserResponse assignRoles(@PathVariable long id, @Valid @RequestBody AssignRolesRequest request) {
        return UserResponse.from(
                userService.assignRoles(id, parseRoles(request.roles()), CurrentUser.operator()));
    }

    /** 启用（重复启用 400） */
    @PostMapping("/{id}/enable")
    public UserResponse enable(@PathVariable long id) {
        return UserResponse.from(userService.enable(id, CurrentUser.operator()));
    }

    /** 停用（重复停用 400；停用后该用户立即不可登录，已签发 token 逐请求校验失效） */
    @PostMapping("/{id}/disable")
    public UserResponse disable(@PathVariable long id) {
        return UserResponse.from(userService.disable(id, CurrentUser.operator()));
    }

    /** 管理员重置密码（无需旧密码；新密码同样过强度校验） */
    @PostMapping("/{id}/password")
    public UserResponse resetPassword(@PathVariable long id, @Valid @RequestBody ResetPasswordRequest request) {
        return UserResponse.from(userService.resetPassword(id, request.password(), CurrentUser.operator()));
    }

    // ---------------------------------------------------------------- 参数解析

    /** 角色名解析（非法值给出友好 400 信息，不透出枚举内部异常） */
    private static Set<Role> parseRoles(List<String> names) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (String name : names) {
            try {
                roles.add(Role.valueOf(name.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "角色仅支持 ADMIN / BOSS / ACCOUNTANT / WAREHOUSE / PURCHASER / SALES: " + name);
            }
        }
        return roles;
    }
}
