package com.sjherp.app.receivable;

import java.util.Locale;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.receivable.ReceivableDtos.PageResponse;
import com.sjherp.app.receivable.ReceivableDtos.ReceivableResponse;
import com.sjherp.domain.receivable.ReceivableQuery;
import com.sjherp.domain.receivable.ReceivableStatus;

/**
 * 应收账款 API（M3-T10）：
 * <ul>
 *   <li>GET /api/receivables?customerId=&status=&page=&size= → 200 分页；</li>
 *   <li>GET /api/receivables/{id} → 200 详情（不存在 404）。</li>
 * </ul>
 *
 * <p>权限（docs/权限矩阵.md）：应收查询要求 {@code sales:invoice}（ADMIN/BOSS/SALES/ACCOUNTANT）
 * ——应收是销售开票的财务产出，与发票同权（受控查询，非公开台账）。
 * 应收只能由销售发票过账生成（无写接口）；核销（收款）M4-T03 落地。
 */
@RestController
@RequestMapping("/api/receivables")
@PreAuthorize("@perm.has('sales:invoice')")
public class ReceivableController {

    private final ReceivableAppService receivableAppService;

    public ReceivableController(ReceivableAppService receivableAppService) {
        this.receivableAppService = receivableAppService;
    }

    /** 分页（customerId、status 可选；按创建倒序） */
    @GetMapping
    public PageResponse<ReceivableResponse> search(
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(receivableAppService.search(
                new ReceivableQuery(customerId, parseStatus(status), page, size)));
    }

    /** 详情（不存在 404） */
    @GetMapping("/{id}")
    public ReceivableResponse get(@PathVariable long id) {
        return ReceivableResponse.from(receivableAppService.get(id));
    }

    private static ReceivableStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ReceivableStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status 非法（OPEN/PARTIAL/SETTLED）: " + status);
        }
    }
}
