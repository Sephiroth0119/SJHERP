package com.sjherp.app.gl;

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

import com.sjherp.app.gl.GlDtos.AccountBalanceResponse;
import com.sjherp.app.gl.GlDtos.CreateVoucherRequest;
import com.sjherp.app.gl.GlDtos.PageResponse;
import com.sjherp.app.gl.GlDtos.TrialBalanceResponse;
import com.sjherp.app.gl.GlDtos.VoucherResponse;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.gl.VoucherQuery;

import jakarta.validation.Valid;

/**
 * 会计凭证 API（M4-T01，全系统最高风险的财务核心）：
 * <ul>
 *   <li>POST /api/gl/vouchers → 201 建凭证（自动 VCH- 编号，账期由凭证日期推算；借贷不平 → 400，验收①）；</li>
 *   <li>POST /api/gl/vouchers/{docNo}/post → 200 过账（DRAFT→APPROVED；关账期 → 409，验收②）；</li>
 *   <li>GET  /api/gl/vouchers/{docNo} → 200 凭证详情（不存在 404）；</li>
 *   <li>GET  /api/gl/vouchers?period=&status=&page=&size= → 200 分页（按创建倒序）；</li>
 *   <li>GET  /api/gl/trial-balance?period= → 200 试算平衡（已过账凭证行按科目汇总，Σ借==Σ贷）；</li>
 *   <li>GET  /api/gl/account-balance?accountCode=&period= → 200 某科目某账期借贷余额。</li>
 * </ul>
 *
 * <p>权限（docs/权限矩阵.md）：凭证整体属受控动作，写/查均须 {@code finance:voucher}
 * （ADMIN/BOSS/ACCOUNTANT，类级 @PreAuthorize 覆盖全部端点）。错误契约见 {@link GlExceptionHandler}。
 * 红字冲销实现留 M4-T07。
 */
@RestController
@RequestMapping("/api/gl")
@PreAuthorize("@perm.has('finance:voucher')")
public class GlVoucherController {

    private final VoucherAppService voucherAppService;

    public GlVoucherController(VoucherAppService voucherAppService) {
        this.voucherAppService = voucherAppService;
    }

    /** 建凭证（草稿，自动 VCH- 编号；借贷不平 → 400，验收①） */
    @PostMapping("/vouchers")
    public ResponseEntity<VoucherResponse> create(@Valid @RequestBody CreateVoucherRequest request) {
        VoucherResponse body = VoucherResponse.from(voucherAppService.create(
                request.voucherDate(), request.summary(), request.lines(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 过账（DRAFT → APPROVED；关账期 → 409，验收②） */
    @PostMapping("/vouchers/{docNo}/post")
    public VoucherResponse post(@PathVariable String docNo) {
        return VoucherResponse.from(voucherAppService.post(docNo, CurrentUser.operator()));
    }

    /** 凭证详情（不存在 404） */
    @GetMapping("/vouchers/{docNo}")
    public VoucherResponse get(@PathVariable String docNo) {
        return VoucherResponse.from(voucherAppService.get(docNo));
    }

    /** 分页（period、status 可选；按创建倒序） */
    @GetMapping("/vouchers")
    public PageResponse<VoucherResponse> search(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.ofVouchers(voucherAppService.search(
                new VoucherQuery(blankToNull(period), parseStatus(status), page, size)));
    }

    /** 试算平衡（某账期已过账凭证行按科目汇总借贷发生额，Σ借==Σ贷） */
    @GetMapping("/trial-balance")
    public TrialBalanceResponse trialBalance(@RequestParam String period) {
        String normalized = period == null ? null : period.strip();
        return TrialBalanceResponse.from(normalized, voucherAppService.trialBalance(normalized));
    }

    /** 某科目某账期已过账借贷余额（无发生额返回零额） */
    @GetMapping("/account-balance")
    public AccountBalanceResponse accountBalance(@RequestParam String accountCode,
                                                 @RequestParam String period) {
        return AccountBalanceResponse.from(voucherAppService.accountBalance(accountCode, period));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            // 校验状态合法性后回传规范名（VoucherQuery/仓储按字符串精确匹配）
            return DocumentStatus.valueOf(status.strip().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status 非法（DRAFT/APPROVED/REVERSED/CANCELLED）: "
                    + status);
        }
    }
}
