package com.sjherp.app.production;

import jakarta.validation.Valid;

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

import com.sjherp.app.production.ProductionReportDtos.CreateRequest;
import com.sjherp.app.production.ProductionReportDtos.ReportResponse;
import com.sjherp.app.production.WorkOrderDtos.PageResponse;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.production.ProductionReportQuery;

/**
 * 报工单 REST API（M5-T05）：
 * <ul>
 *   <li>POST  /api/production/reports                     → 201 建单</li>
 *   <li>POST  /api/production/reports/{docNo}/approve     → 200 审核</li>
 *   <li>POST  /api/production/reports/{docNo}/post        → 200 过账（完工入库）</li>
 *   <li>POST  /api/production/reports/{docNo}/cancel      → 200 作废</li>
 *   <li>GET   /api/production/reports?workOrderDocNo=&status=&page=&size= → 200 列表</li>
 *   <li>GET   /api/production/reports/{docNo}             → 200 详情，不存在 404</li>
 * </ul>
 *
 * <p>权限：类级 {@code @PreAuthorize("@perm.has('production:report')")}。
 * 错误响应见 {@link ProductionExceptionHandler}。
 */
@RestController
@RequestMapping("/api/production/reports")
@PreAuthorize("@perm.has('production:report')")
public class ProductionReportController {

    private final ProductionReportAppService appService;

    public ProductionReportController(ProductionReportAppService appService) {
        this.appService = appService;
    }

    // ---------------------------------------------------------------- 建单

    /** 建报工单（草稿）→ 201 */
    @PostMapping
    public ResponseEntity<ReportResponse> create(@Valid @RequestBody CreateRequest request) {
        var lines = request.lines().stream()
                .map(ProductionReportDtos::toInput)
                .toList();
        ReportResponse body = ReportResponse.from(
                appService.create(request.workOrderDocNo(), request.warehouseId(),
                        request.productId(), request.completedQty(), request.scrapQty(),
                        request.unitId(), request.remark(), lines, CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // ---------------------------------------------------------------- 状态流转

    /** 审核报工单（DRAFT → APPROVED） */
    @PostMapping("/{docNo}/approve")
    public ReportResponse approve(@PathVariable String docNo) {
        return ReportResponse.from(appService.approve(docNo, CurrentUser.operator()));
    }

    /** 过账报工单（APPROVED → COMPLETED），PRODUCTION_IN 完工入库。 */
    @PostMapping("/{docNo}/post")
    public ReportResponse post(@PathVariable String docNo) {
        return ReportResponse.from(appService.post(docNo, CurrentUser.operator()));
    }

    /** 作废报工单（DRAFT → CANCELLED） */
    @PostMapping("/{docNo}/cancel")
    public ReportResponse cancel(@PathVariable String docNo) {
        return ReportResponse.from(appService.cancel(docNo, CurrentUser.operator()));
    }

    // ---------------------------------------------------------------- 查询

    /** 分页查询报工单列表 */
    @GetMapping
    public PageResponse<ReportResponse> search(
            @RequestParam(required = false) String workOrderDocNo,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        DocumentStatus docStatus = status != null ? DocumentStatus.valueOf(status) : null;
        ProductionReportQuery query = new ProductionReportQuery(workOrderDocNo, docStatus, page, size);
        var result = appService.search(query);
        var items = result.items().stream().map(ReportResponse::from).toList();
        return new PageResponse<>(items, result.total(), result.page(), result.size());
    }

    /** 按单号查询报工单详情 */
    @GetMapping("/{docNo}")
    public ReportResponse get(@PathVariable String docNo) {
        return ReportResponse.from(appService.get(docNo));
    }
}
