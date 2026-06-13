package com.sjherp.app.gl;

import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.gl.GlDtos.AccountResponse;
import com.sjherp.app.gl.GlDtos.CreateAccountRequest;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.gl.Account;
import com.sjherp.domain.gl.AccountType;

import jakarta.validation.Valid;

/**
 * 会计科目 API（M4-T01）：
 * <ul>
 *   <li>POST /api/gl/accounts → 201 建科目（编码唯一、上级存在且非末级，层级由上级推算）；</li>
 *   <li>POST /api/gl/accounts/{code}/disable → 200 停用（预置科目禁停用 → 400）；</li>
 *   <li>POST /api/gl/accounts/{code}/enable → 200 启用；</li>
 *   <li>GET  /api/gl/accounts?leafOnly=&type= → 200 科目列表（按编码升序）；</li>
 *   <li>GET  /api/gl/accounts/{code} → 200 科目详情（不存在 404）。</li>
 * </ul>
 *
 * <p>权限（docs/权限矩阵.md）：写操作须 {@code finance:account}（ADMIN/BOSS/ACCOUNTANT，逐方法标注）；
 * 科目表/末级科目查询照例不设权限点（登录即可，查询方法不加 @PreAuthorize，走默认 authenticated 规则）。
 * 错误契约见 {@link GlExceptionHandler}（编码重复/上级非末级/预置禁停用等 → 400）。
 */
@RestController
@RequestMapping("/api/gl/accounts")
public class GlAccountController {

    private final AccountAppService accountAppService;

    public GlAccountController(AccountAppService accountAppService) {
        this.accountAppService = accountAppService;
    }

    /** 建科目（编码唯一、上级存在且非末级，层级由上级推算） */
    @PreAuthorize("@perm.has('finance:account')")
    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse body = AccountResponse.from(accountAppService.create(
                request.code(), request.name(), request.type(), request.balanceDir(),
                request.parentCode(), Boolean.TRUE.equals(request.isLeaf()), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 停用（预置科目禁停用 → 400） */
    @PreAuthorize("@perm.has('finance:account')")
    @PostMapping("/{code}/disable")
    public AccountResponse disable(@PathVariable String code) {
        return AccountResponse.from(accountAppService.disable(code, CurrentUser.operator()));
    }

    /** 启用 */
    @PreAuthorize("@perm.has('finance:account')")
    @PostMapping("/{code}/enable")
    public AccountResponse enable(@PathVariable String code) {
        return AccountResponse.from(accountAppService.enable(code, CurrentUser.operator()));
    }

    /** 科目列表（leafOnly=true 仅末级；type 按类别过滤；均按编码升序）——查询登录即可 */
    @GetMapping
    public List<AccountResponse> list(
            @RequestParam(required = false, defaultValue = "false") boolean leafOnly,
            @RequestParam(required = false) String type) {
        List<Account> accounts = leafOnly ? accountAppService.listLeaf() : accountAppService.listAll();
        AccountType filterType = parseTypeFilter(type);
        return accounts.stream()
                .filter(account -> filterType == null || account.getType() == filterType)
                .map(AccountResponse::from)
                .toList();
    }

    /** 科目详情（不存在 404）——查询登录即可 */
    @GetMapping("/{code}")
    public AccountResponse get(@PathVariable String code) {
        return AccountResponse.from(accountAppService.get(code));
    }

    private static AccountType parseTypeFilter(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return AccountType.valueOf(type.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("科目类别非法（ASSET/LIABILITY/EQUITY/COST/PROFIT_LOSS）: "
                    + type);
        }
    }
}
