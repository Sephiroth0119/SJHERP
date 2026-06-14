package com.sjherp.app.finance;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.finance.FinancialStatementDtos.BalanceSheet;
import com.sjherp.app.finance.FinancialStatementDtos.IncomeStatement;

/**
 * 财务报表 API（M4-T06，<b>只读</b>查询，与账龄/T12 报表同前缀 /api/reports）：
 * <ul>
 *   <li>GET /api/reports/balance-sheet?period=yyyyMM → 资产负债表（时点，Assets=Liab+Equity）；</li>
 *   <li>GET /api/reports/income-statement?period=yyyyMM → 利润表（本期 + 本年累计）。</li>
 * </ul>
 *
 * <p>权限（docs/权限矩阵.md）：两报表暴露全盘财务状况（敏感），均须 {@code finance:report}
 * （ADMIN/BOSS/ACCOUNTANT）。全部经 {@link FinancialStatementService}→{@link FinancialStatementDao}
 * 只读 SQL（CLAUDE.md「报表只读查询除外」），绝不写库。
 * 错误契约：period 缺失/格式非法 → 400 {"error": "..."}（口径同 {@code AgingReportController}）。
 */
@RestController
@RequestMapping("/api/reports")
@PreAuthorize("@perm.has('finance:report')")
public class FinancialStatementController {

    /** 账期键格式：yyyyMM 6 位数字（与 voucher.period CHAR(6) 一致）。 */
    private static final Pattern PERIOD_PATTERN = Pattern.compile("\\d{6}");

    private final FinancialStatementService service;

    public FinancialStatementController(FinancialStatementService service) {
        this.service = Objects.requireNonNull(service, "service 不能为空");
    }

    /** 资产负债表：period 必填（yyyyMM）。 */
    @GetMapping("/balance-sheet")
    public BalanceSheet balanceSheet(@RequestParam(required = false) String period) {
        return service.balanceSheet(requirePeriod(period));
    }

    /** 利润表：period 必填（yyyyMM）；内部算本期 [P,P] + 本年累计 [yyyy01,P]。 */
    @GetMapping("/income-statement")
    public IncomeStatement incomeStatement(@RequestParam(required = false) String period) {
        return service.incomeStatement(requirePeriod(period));
    }

    /** period 必填 + 格式校验（缺/错 → IllegalArgumentException → 400）。 */
    private static String requirePeriod(String period) {
        if (period == null || period.isBlank()) {
            throw new IllegalArgumentException("period 不能为空（格式 yyyyMM，如 202606）");
        }
        String trimmed = period.strip();
        if (!PERIOD_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("period 格式非法，应为 yyyyMM 6 位数字: " + trimmed);
        }
        return trimmed;
    }

    /** 参数不合法 → 400 {"error": "..."}（口径同其它模块异常契约）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> onIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
