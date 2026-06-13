package com.sjherp.app.sales;

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

import com.sjherp.app.security.CurrentUser;
import com.sjherp.app.sales.SalesDtos.PageResponse;
import com.sjherp.app.sales.SalesDtos.SalesOrderCreateRequest;
import com.sjherp.app.sales.SalesDtos.SalesOrderCreateResponse;
import com.sjherp.app.sales.SalesDtos.SalesOrderResponse;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.sales.SalesOrderQuery;

import jakarta.validation.Valid;

/**
 * 销售订单 API（M3-T08）：
 * <ul>
 *   <li>POST /api/sales/orders → 201 建单（自动 SO- 编号；返回单据 + 可用库存不足警告）；</li>
 *   <li>POST /api/sales/orders/{docNo}/approve → 200 审核（DRAFT→APPROVED）；</li>
 *   <li>POST /api/sales/orders/{docNo}/cancel → 200 作废（仅 DRAFT）；</li>
 *   <li>GET  /api/sales/orders/{docNo} → 200 单据详情（不存在 404）；</li>
 *   <li>GET  /api/sales/orders?customerId=&status=&page=&size= → 200 分页。</li>
 * </ul>
 *
 * <p>权限（docs/权限矩阵.md）：写/查均须 {@code sales:order}（ADMIN/BOSS/SALES）。
 * 错误契约见 {@link SalesExceptionHandler}。下单不动库存，可用库存不足仅警告不阻断。
 */
@RestController
@RequestMapping("/api/sales/orders")
@PreAuthorize("@perm.has('sales:order')")
public class SalesOrderController {

    private final SalesOrderAppService salesOrderAppService;

    public SalesOrderController(SalesOrderAppService salesOrderAppService) {
        this.salesOrderAppService = salesOrderAppService;
    }

    /** 建单（草稿，自动编号；返回单据 + 可用库存不足警告） */
    @PostMapping
    public ResponseEntity<SalesOrderCreateResponse> create(@Valid @RequestBody SalesOrderCreateRequest request) {
        SalesOrderCreateResponse body = SalesOrderCreateResponse.from(salesOrderAppService.create(
                request.customerId(), request.orderDate(), request.remark(),
                request.checkWarehouseId(), request.lines(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 审核（DRAFT → APPROVED） */
    @PostMapping("/{docNo}/approve")
    public SalesOrderResponse approve(@PathVariable String docNo) {
        return SalesOrderResponse.from(salesOrderAppService.approve(docNo, CurrentUser.operator()));
    }

    /** 作废（仅 DRAFT） */
    @PostMapping("/{docNo}/cancel")
    public SalesOrderResponse cancel(@PathVariable String docNo) {
        return SalesOrderResponse.from(salesOrderAppService.cancel(docNo, CurrentUser.operator()));
    }

    /** 单据详情（不存在 404） */
    @GetMapping("/{docNo}")
    public SalesOrderResponse get(@PathVariable String docNo) {
        return SalesOrderResponse.from(salesOrderAppService.get(docNo));
    }

    /** 分页（customerId、status 可选；按创建倒序） */
    @GetMapping
    public PageResponse<SalesOrderResponse> search(
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.ofOrders(salesOrderAppService.search(
                new SalesOrderQuery(customerId, parseStatus(status), page, size)));
    }

    private static DocumentStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return DocumentStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status 非法（DRAFT/APPROVED/EXECUTING/COMPLETED/"
                    + "CANCELLED/REVERSED）: " + status);
        }
    }
}
