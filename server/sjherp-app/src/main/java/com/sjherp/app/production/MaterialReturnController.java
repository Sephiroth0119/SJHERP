package com.sjherp.app.production;

import com.sjherp.app.production.MaterialReturnDtos.CreateRequest;
import com.sjherp.app.production.MaterialReturnDtos.ReturnResponse;
import com.sjherp.app.production.WorkOrderDtos.PageResponse;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.production.MaterialReturnQuery;

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
 * 退料单 REST API（M5-T04）：
 * <ul>
 *   <li>POST  /api/production/material-returns              → 201 建单</li>
 *   <li>POST  /api/production/material-returns/{docNo}/approve → 200 审核</li>
 *   <li>POST  /api/production/material-returns/{docNo}/post    → 200 过账</li>
 *   <li>GET   /api/production/material-returns?materialIssueDocNo=&status=&page=&size= → 200 列表</li>
 *   <li>GET   /api/production/material-returns/{docNo}      → 200 详情，不存在 404</li>
 * </ul>
 *
 * <p>权限：类级 {@code @PreAuthorize("@perm.has('production:material')")}。
 * 错误响应见 {@link ProductionExceptionHandler}。
 */
@RestController
@RequestMapping("/api/production/material-returns")
@PreAuthorize("@perm.has('production:material')")
public class MaterialReturnController {

    private final MaterialReturnAppService appService;

    public MaterialReturnController(MaterialReturnAppService appService) {
        this.appService = appService;
    }

    // ---------------------------------------------------------------- 建单

    /** 建退料单（草稿）→ 201 */
    @PostMapping
    public ResponseEntity<ReturnResponse> create(@Valid @RequestBody CreateRequest request) {
        var lines = request.lines().stream()
                .map(MaterialReturnDtos::toInput)
                .toList();
        ReturnResponse body = ReturnResponse.from(
                appService.create(request.materialIssueDocNo(), request.warehouseId(),
                        request.remark(), lines, CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // ---------------------------------------------------------------- 状态流转

    /** 审核退料单（DRAFT → APPROVED） */
    @PostMapping("/{docNo}/approve")
    public ReturnResponse approve(@PathVariable String docNo) {
        return ReturnResponse.from(appService.approve(docNo, CurrentUser.operator()));
    }

    /** 过账退料单（APPROVED → COMPLETED），按原领料成本 PRODUCTION_RETURN 入库。 */
    @PostMapping("/{docNo}/post")
    public ReturnResponse post(@PathVariable String docNo) {
        return ReturnResponse.from(appService.post(docNo, CurrentUser.operator()));
    }

    // ---------------------------------------------------------------- 查询

    /** 分页查询退料单列表 */
    @GetMapping
    public PageResponse<ReturnResponse> search(
            @RequestParam(required = false) String materialIssueDocNo,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        DocumentStatus docStatus = status != null ? DocumentStatus.valueOf(status) : null;
        MaterialReturnQuery query = new MaterialReturnQuery(materialIssueDocNo, docStatus, page, size);
        var result = appService.search(query);
        var items = result.items().stream().map(ReturnResponse::from).toList();
        return new PageResponse<>(items, result.total(), result.page(), result.size());
    }

    /** 按单号查询退料单详情 */
    @GetMapping("/{docNo}")
    public ReturnResponse get(@PathVariable String docNo) {
        return ReturnResponse.from(appService.get(docNo));
    }
}
