package com.sjherp.app.production;

import com.sjherp.app.config.TransactionalWorkOrderService;
import com.sjherp.app.production.WorkOrderDtos.CreateFromSuggestionRequest;
import com.sjherp.app.production.WorkOrderDtos.CreateWorkOrderRequest;
import com.sjherp.app.production.WorkOrderDtos.PageResponse;
import com.sjherp.app.production.WorkOrderDtos.WorkOrderResponse;
import com.sjherp.app.production.WorkOrderDtos.WorkOrderSummaryResponse;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.production.WorkOrderQuery;

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
 * 工单 REST API（M5-T03）：
 * <ul>
 *   <li>POST   /api/production/work-orders              → 201 手工建单</li>
 *   <li>POST   /api/production/work-orders/from-mrp     → 201 从 MRP 建议建单</li>
 *   <li>POST   /api/production/work-orders/{docNo}/release  → 200 下达（DRAFT→APPROVED）</li>
 *   <li>POST   /api/production/work-orders/{docNo}/start    → 200 开工（APPROVED→EXECUTING）</li>
 *   <li>POST   /api/production/work-orders/{docNo}/complete → 200 完工（EXECUTING→COMPLETED）</li>
 *   <li>POST   /api/production/work-orders/{docNo}/cancel   → 200 作废（DRAFT→CANCELLED）</li>
 *   <li>POST   /api/production/work-orders/{docNo}/reverse  → 200 冲销（APPROVED→REVERSED）</li>
 *   <li>GET    /api/production/work-orders?productId=&status=&page=&size= → 200 分页列表</li>
 *   <li>GET    /api/production/work-orders/{docNo}      → 200 工单详情，不存在 404</li>
 * </ul>
 *
 * <p>权限：类级 {@code @PreAuthorize("@perm.has('production:wo')")}，ADMIN/BOSS 拥有该权限。
 * 错误响应见 {@link ProductionExceptionHandler}。
 */
@RestController
@RequestMapping("/api/production/work-orders")
@PreAuthorize("@perm.has('production:wo')")
public class WorkOrderController {

    private final TransactionalWorkOrderService woService;

    public WorkOrderController(TransactionalWorkOrderService woService) {
        this.woService = woService;
    }

    // ---------------------------------------------------------------- 建单

    /** 手工建单 → 201 */
    @PostMapping
    public ResponseEntity<WorkOrderResponse> createManual(
            @Valid @RequestBody CreateWorkOrderRequest request) {
        WorkOrderResponse body = WorkOrderResponse.from(
                woService.createManual(
                        request.productId(), request.plannedQty(), request.unitId(),
                        request.bomVersion(), request.routingVersion(), request.warehouseId(),
                        request.plannedStartDate(), request.plannedEndDate(),
                        request.remark(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 从 MRP 生产建议建单 → 201 */
    @PostMapping("/from-mrp")
    public ResponseEntity<WorkOrderResponse> createFromSuggestion(
            @Valid @RequestBody CreateFromSuggestionRequest request) {
        WorkOrderResponse body = WorkOrderResponse.from(
                woService.createFromSuggestion(
                        request.mrpRunDocNo(), request.productId(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // ---------------------------------------------------------------- 状态流转

    /** 下达工单（DRAFT → APPROVED） */
    @PostMapping("/{docNo}/release")
    public WorkOrderResponse release(@PathVariable String docNo) {
        return WorkOrderResponse.from(woService.release(docNo, CurrentUser.operator()));
    }

    /** 开工（APPROVED → EXECUTING） */
    @PostMapping("/{docNo}/start")
    public WorkOrderResponse start(@PathVariable String docNo) {
        return WorkOrderResponse.from(woService.start(docNo, CurrentUser.operator()));
    }

    /** 完工（EXECUTING → COMPLETED） */
    @PostMapping("/{docNo}/complete")
    public WorkOrderResponse complete(@PathVariable String docNo) {
        return WorkOrderResponse.from(woService.complete(docNo, CurrentUser.operator()));
    }

    /** 作废工单（DRAFT → CANCELLED） */
    @PostMapping("/{docNo}/cancel")
    public WorkOrderResponse cancel(@PathVariable String docNo) {
        return WorkOrderResponse.from(woService.cancel(docNo, CurrentUser.operator()));
    }

    /** 冲销工单（APPROVED → REVERSED） */
    @PostMapping("/{docNo}/reverse")
    public WorkOrderResponse reverse(@PathVariable String docNo) {
        return WorkOrderResponse.from(woService.reverse(docNo, CurrentUser.operator()));
    }

    // ---------------------------------------------------------------- 查询

    /** 分页查询工单列表 */
    @GetMapping
    public PageResponse<WorkOrderSummaryResponse> search(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        DocumentStatus docStatus = status != null ? DocumentStatus.valueOf(status) : null;
        WorkOrderQuery query = new WorkOrderQuery(productId, docStatus, page, size);
        var result = woService.search(query);
        var items = result.items().stream().map(WorkOrderSummaryResponse::from).toList();
        return new PageResponse<>(items, result.total(), result.page(), result.size());
    }

    /** 按单号查询工单详情 */
    @GetMapping("/{docNo}")
    public WorkOrderResponse get(@PathVariable String docNo) {
        return WorkOrderResponse.from(woService.get(docNo));
    }
}
