package com.sjherp.app.production;

import com.sjherp.app.production.MaterialIssueDtos.CreateRequest;
import com.sjherp.app.production.MaterialIssueDtos.IssueResponse;
import com.sjherp.app.production.WorkOrderDtos.PageResponse;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.production.MaterialIssueQuery;

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

/**
 * 领料单 REST API（M5-T04）：
 * <ul>
 *   <li>POST  /api/production/material-issues              → 201 建单</li>
 *   <li>POST  /api/production/material-issues/{docNo}/approve → 200 审核</li>
 *   <li>POST  /api/production/material-issues/{docNo}/post    → 200 过账</li>
 *   <li>POST  /api/production/material-issues/{docNo}/cancel  → 200 作废</li>
 *   <li>GET   /api/production/material-issues?workOrderDocNo=&status=&page=&size= → 200 列表</li>
 *   <li>GET   /api/production/material-issues/{docNo}      → 200 详情，不存在 404</li>
 * </ul>
 *
 * <p>权限：类级 {@code @PreAuthorize("@perm.has('production:material')")}。
 * 错误响应见 {@link ProductionExceptionHandler}。
 */
@RestController
@RequestMapping("/api/production/material-issues")
@PreAuthorize("@perm.has('production:material')")
public class MaterialIssueController {

    private final MaterialIssueAppService appService;

    public MaterialIssueController(MaterialIssueAppService appService) {
        this.appService = appService;
    }

    // ---------------------------------------------------------------- 建单

    /** 建领料单（草稿）→ 201 */
    @PostMapping
    public ResponseEntity<IssueResponse> create(@Valid @RequestBody CreateRequest request) {
        var lines = request.lines().stream()
                .map(MaterialIssueDtos::toInput)
                .toList();
        IssueResponse body = IssueResponse.from(
                appService.create(request.workOrderDocNo(), request.warehouseId(),
                        request.remark(), lines, CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // ---------------------------------------------------------------- 状态流转

    /** 审核领料单（DRAFT → APPROVED） */
    @PostMapping("/{docNo}/approve")
    public IssueResponse approve(@PathVariable String docNo) {
        return IssueResponse.from(appService.approve(docNo, CurrentUser.operator()));
    }

    /** 过账领料单（APPROVED → COMPLETED），批量 PRODUCTION_ISSUE 出库。 */
    @PostMapping("/{docNo}/post")
    public IssueResponse post(@PathVariable String docNo) {
        return IssueResponse.from(appService.post(docNo, CurrentUser.operator()));
    }

    /** 作废领料单（DRAFT → CANCELLED） */
    @PostMapping("/{docNo}/cancel")
    public IssueResponse cancel(@PathVariable String docNo) {
        return IssueResponse.from(appService.cancel(docNo, CurrentUser.operator()));
    }

    // ---------------------------------------------------------------- 查询

    /** 分页查询领料单列表 */
    @GetMapping
    public PageResponse<IssueResponse> search(
            @RequestParam(required = false) String workOrderDocNo,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        DocumentStatus docStatus = status != null ? DocumentStatus.valueOf(status) : null;
        MaterialIssueQuery query = new MaterialIssueQuery(workOrderDocNo, docStatus, page, size);
        var result = appService.search(query);
        var items = result.items().stream().map(IssueResponse::from).toList();
        return new PageResponse<>(items, result.total(), result.page(), result.size());
    }

    /** 按单号查询领料单详情 */
    @GetMapping("/{docNo}")
    public IssueResponse get(@PathVariable String docNo) {
        return IssueResponse.from(appService.get(docNo));
    }
}
