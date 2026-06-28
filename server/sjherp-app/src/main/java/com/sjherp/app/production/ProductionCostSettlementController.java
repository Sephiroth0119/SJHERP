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

import com.sjherp.app.production.ProductionCostSettlementDtos.CreateRequest;
import com.sjherp.app.production.ProductionCostSettlementDtos.SettlementResponse;
import com.sjherp.app.production.WorkOrderDtos.PageResponse;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.production.ProductionCostSettlementQuery;

/**
 * 月末成本结转单 REST API（M5-T06）：
 * <ul>
 *   <li>POST  /api/production/cost-settlements                 → 201 建单（按账期归集料工费 + 约当法分摊）</li>
 *   <li>POST  /api/production/cost-settlements/{docNo}/approve → 200 审核</li>
 *   <li>POST  /api/production/cost-settlements/{docNo}/post    → 200 过账（CostAdjust + GL）</li>
 *   <li>POST  /api/production/cost-settlements/{docNo}/cancel  → 200 作废</li>
 *   <li>GET   /api/production/cost-settlements?period=&status=&page=&size= → 200 列表</li>
 *   <li>GET   /api/production/cost-settlements/{docNo}         → 200 详情，不存在 404</li>
 * </ul>
 *
 * <p>权限：类级 {@code @PreAuthorize("@perm.has('production:cost')")}（全程受控，含查询——成本是会计动作）。
 * 错误响应见 {@link ProductionExceptionHandler}。
 */
@RestController
@RequestMapping("/api/production/cost-settlements")
@PreAuthorize("@perm.has('production:cost')")
public class ProductionCostSettlementController {

    private final ProductionCostSettlementAppService appService;

    public ProductionCostSettlementController(ProductionCostSettlementAppService appService) {
        this.appService = appService;
    }

    /** 建成本结转单（草稿）→ 201 */
    @PostMapping
    public ResponseEntity<SettlementResponse> create(@Valid @RequestBody CreateRequest request) {
        var lines = request.lines().stream()
                .map(ProductionCostSettlementDtos::toInput)
                .toList();
        SettlementResponse body = SettlementResponse.from(
                appService.create(request.period(), request.remark(), lines, CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 审核（DRAFT → APPROVED） */
    @PostMapping("/{docNo}/approve")
    public SettlementResponse approve(@PathVariable String docNo) {
        return SettlementResponse.from(appService.approve(docNo, CurrentUser.operator()));
    }

    /** 过账（APPROVED → COMPLETED），CostAdjust 追加完工工费 + 出 GL。 */
    @PostMapping("/{docNo}/post")
    public SettlementResponse post(@PathVariable String docNo) {
        return SettlementResponse.from(appService.post(docNo, CurrentUser.operator()));
    }

    /** 作废（DRAFT → CANCELLED） */
    @PostMapping("/{docNo}/cancel")
    public SettlementResponse cancel(@PathVariable String docNo) {
        return SettlementResponse.from(appService.cancel(docNo, CurrentUser.operator()));
    }

    /** 分页查询列表 */
    @GetMapping
    public PageResponse<SettlementResponse> search(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        DocumentStatus docStatus = status != null ? DocumentStatus.valueOf(status) : null;
        ProductionCostSettlementQuery query = new ProductionCostSettlementQuery(period, docStatus, page, size);
        var result = appService.search(query);
        var items = result.items().stream().map(SettlementResponse::from).toList();
        return new PageResponse<>(items, result.total(), result.page(), result.size());
    }

    /** 按单号查询详情 */
    @GetMapping("/{docNo}")
    public SettlementResponse get(@PathVariable String docNo) {
        return SettlementResponse.from(appService.get(docNo));
    }
}
