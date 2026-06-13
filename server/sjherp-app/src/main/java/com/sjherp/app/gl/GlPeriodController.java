package com.sjherp.app.gl;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.gl.GlDtos.OpenPeriodRequest;
import com.sjherp.app.gl.GlDtos.PeriodResponse;
import com.sjherp.app.security.CurrentUser;

import jakarta.validation.Valid;

/**
 * 会计期间 API（M4-T01）：
 * <ul>
 *   <li>POST /api/gl/periods → 201 开启账期（yyyyMM，已存在 → 400）；</li>
 *   <li>POST /api/gl/periods/{period}/close → 200 关账（OPEN→CLOSED，重复关账 → 409）；</li>
 *   <li>POST /api/gl/periods/{period}/reopen → 200 重开（CLOSED→OPEN，高敏，重复重开 → 409）；</li>
 *   <li>GET  /api/gl/periods → 200 账期列表（按账期键升序）；</li>
 *   <li>GET  /api/gl/periods/{period} → 200 账期详情（不存在 404）。</li>
 * </ul>
 *
 * <p>权限（docs/权限矩阵.md）：开启/关账须 {@code finance:period}（ADMIN/BOSS/ACCOUNTANT）；
 * 重开须高敏权限 {@code finance:period_reopen}（仅 ADMIN/BOSS，CLAUDE.md 原则 2：期间不可随意重开）。
 * 账期列表/详情查询照例不设权限点（登录即可，查询方法不加 @PreAuthorize，走默认 authenticated 规则）。
 */
@RestController
@RequestMapping("/api/gl/periods")
public class GlPeriodController {

    private final AccountingPeriodAppService accountingPeriodAppService;

    public GlPeriodController(AccountingPeriodAppService accountingPeriodAppService) {
        this.accountingPeriodAppService = accountingPeriodAppService;
    }

    /** 开启账期（yyyyMM，已存在 → 400） */
    @PreAuthorize("@perm.has('finance:period')")
    @PostMapping
    public ResponseEntity<PeriodResponse> open(@Valid @RequestBody OpenPeriodRequest request) {
        PeriodResponse body = PeriodResponse.from(
                accountingPeriodAppService.open(request.period(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 关账（OPEN→CLOSED，重复关账 → 409；T01 只改状态，结转留 T05） */
    @PreAuthorize("@perm.has('finance:period')")
    @PostMapping("/{period}/close")
    public PeriodResponse close(@PathVariable String period) {
        return PeriodResponse.from(accountingPeriodAppService.close(period, CurrentUser.operator()));
    }

    /** 重开账期（高敏，仅 ADMIN/BOSS）：CLOSED→OPEN，清空关账标记（重复重开 → 409） */
    @PreAuthorize("@perm.has('finance:period_reopen')")
    @PostMapping("/{period}/reopen")
    public PeriodResponse reopen(@PathVariable String period) {
        return PeriodResponse.from(accountingPeriodAppService.reopen(period, CurrentUser.operator()));
    }

    /** 账期列表（按账期键升序）——查询登录即可 */
    @GetMapping
    public List<PeriodResponse> list() {
        return accountingPeriodAppService.listAll().stream().map(PeriodResponse::from).toList();
    }

    /** 账期详情（不存在 404）——查询登录即可 */
    @GetMapping("/{period}")
    public PeriodResponse get(@PathVariable String period) {
        return PeriodResponse.from(accountingPeriodAppService.get(period));
    }
}
