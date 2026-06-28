package com.sjherp.app.production;

import com.sjherp.app.production.KittingCheckDtos.KittingCheckResponse;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 齐套检查 REST API（M5-T04）：
 * <ul>
 *   <li>GET /api/production/kitting-check?workOrderDocNo=&warehouseId= → 200 检查结果</li>
 * </ul>
 *
 * <p>只读端点，不改变任何单据/库存状态，仅供决策参考。
 * 权限：类级 {@code @PreAuthorize("@perm.has('production:material')")}。
 * 错误响应见 {@link ProductionExceptionHandler}。
 */
@RestController
@RequestMapping("/api/production/kitting-check")
@PreAuthorize("@perm.has('production:material')")
public class KittingCheckController {

    private final KittingCheckAppService appService;

    public KittingCheckController(KittingCheckAppService appService) {
        this.appService = appService;
    }

    /** 执行齐套检查（只读） */
    @GetMapping
    public KittingCheckResponse check(
            @RequestParam String workOrderDocNo,
            @RequestParam long warehouseId) {
        return KittingCheckResponse.from(appService.check(workOrderDocNo, warehouseId));
    }
}
