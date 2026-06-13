package com.sjherp.app.finance;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.finance.AgingDtos.AgingReportResponse;

/**
 * 应收应付账龄分析 API（M4-T03，<b>只读</b>查询，与 T12 报表同前缀 /api/reports）：
 * <ul>
 *   <li>GET /api/reports/receivable-aging?asOf=&customerId=&page=&size= → 应收账龄（按客户分桶汇总）；</li>
 *   <li>GET /api/reports/payable-aging?asOf=&supplierId=&page=&size= → 应付账龄（按供应商分桶汇总）。</li>
 * </ul>
 *
 * <p>权限（docs/权限矩阵.md）：账龄暴露对手方余额与逾期（敏感），两端点均须 {@code finance:settlement}
 * （ADMIN/BOSS/ACCOUNTANT）。{@code asOf} 缺省今天（{@link LocalDate#now()}）。
 * 全部经 {@link AgingReportDao} 只读 SQL（CLAUDE.md「报表只读查询除外」），绝不写库。
 * 错误契约：参数不合法 → 400 {"error": "..."}（口径同其它模块）。
 */
@RestController
@RequestMapping("/api/reports")
@PreAuthorize("@perm.has('finance:settlement')")
public class AgingReportController {

    private final AgingReportDao agingReportDao;

    public AgingReportController(AgingReportDao agingReportDao) {
        this.agingReportDao = Objects.requireNonNull(agingReportDao, "agingReportDao 不能为空");
    }

    /** 应收账龄：asOf（缺省今天）、customerId 可选；按客户分桶汇总 + grandTotal。 */
    @GetMapping("/receivable-aging")
    public AgingReportResponse receivableAging(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) Long customerId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return AgingReportResponse.from(
                agingReportDao.receivableAging(defaultAsOf(asOf), customerId, page, size));
    }

    /** 应付账龄：asOf（缺省今天）、supplierId 可选；按供应商分桶汇总 + grandTotal。 */
    @GetMapping("/payable-aging")
    public AgingReportResponse payableAging(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return AgingReportResponse.from(
                agingReportDao.payableAging(defaultAsOf(asOf), supplierId, page, size));
    }

    /** asOf 缺省今天（app 层取当前日合法）。 */
    private static LocalDate defaultAsOf(LocalDate asOf) {
        return asOf == null ? LocalDate.now() : asOf;
    }

    /** 参数不合法 → 400 {"error": "..."}（口径同其它模块异常契约）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> onIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
