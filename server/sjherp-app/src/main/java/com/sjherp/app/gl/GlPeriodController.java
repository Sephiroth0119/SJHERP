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
import com.sjherp.app.gl.GlDtos.PeriodCloseReadiness;
import com.sjherp.app.gl.GlDtos.PeriodCloseResult;
import com.sjherp.app.gl.GlDtos.PeriodResponse;
import com.sjherp.app.security.CurrentUser;

import jakarta.validation.Valid;

/**
 * 会计期间 API（M4-T01）：
 * <ul>
 *   <li>POST /api/gl/periods → 201 开启账期（yyyyMM，已存在 → 400）；</li>
 *   <li>POST /api/gl/periods/{period}/close → 200 月末结转关账（M4-T05：闸门+结转+断言+关账，
 *       返回 {@link PeriodCloseResult}；被闸门拒 → 409 携 reasons）；</li>
 *   <li>GET  /api/gl/periods/{period}/close-precheck → 200 关账可行性预检（M4-T05，
 *       返回 {@link PeriodCloseReadiness}）；</li>
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
    private final PeriodCloseService periodCloseService;

    public GlPeriodController(AccountingPeriodAppService accountingPeriodAppService,
                             PeriodCloseService periodCloseService) {
        this.accountingPeriodAppService = accountingPeriodAppService;
        this.periodCloseService = periodCloseService;
    }

    /** 开启账期（yyyyMM，已存在 → 400） */
    @PreAuthorize("@perm.has('finance:period')")
    @PostMapping
    public ResponseEntity<PeriodResponse> open(@Valid @RequestBody OpenPeriodRequest request) {
        PeriodResponse body = PeriodResponse.from(
                accountingPeriodAppService.open(request.period(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * 月末结转关账（M4-T05）：编排「结转前一致性闸门 → 损益结转凭证 → 试算平衡断言 → 关账」，
     * 单一事务原子。被闸门拒（账期非 OPEN / 已存在结转凭证 / 一致性 ERROR）→ 409 携 reasons。
     * 返回结转凭证号/净利润/试算平衡等关账结果。{@code finance:period} 不变。
     */
    @PreAuthorize("@perm.has('finance:period')")
    @PostMapping("/{period}/close")
    public PeriodCloseResult close(@PathVariable String period) {
        return periodCloseService.close(period, CurrentUser.operator());
    }

    /**
     * 关账可行性预检（M4-T05，只读）：返回是否可关账、ERROR/WARN 一致性清单、损益结转预览、
     * 净利润、试算平衡 Σ借/Σ贷、账期态。供前端向导与 Agent 引导先看"能不能关、关了什么样"。
     * 关账族操作统一口径，权限取 {@code finance:period}（拆解 §2.3 裁定）。
     */
    @PreAuthorize("@perm.has('finance:period')")
    @GetMapping("/{period}/close-precheck")
    public PeriodCloseReadiness closePrecheck(@PathVariable String period) {
        return periodCloseService.precheck(period);
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
