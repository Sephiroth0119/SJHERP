package com.sjherp.app.purchase;

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

import com.sjherp.app.purchase.PurchaseDtos.CreatePurchaseOrderRequest;
import com.sjherp.app.purchase.PurchaseDtos.PageResponse;
import com.sjherp.app.purchase.PurchaseDtos.PurchaseOrderResponse;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.purchase.PurchaseOrderQuery;

import jakarta.validation.Valid;

/**
 * 采购订单 API（M3-T05）：
 * <ul>
 *   <li>POST /api/purchase/orders → 201 建单（自动 PO- 编号，下单不动库存）；</li>
 *   <li>POST /api/purchase/orders/{docNo}/approve → 200 审核（DRAFT→APPROVED）；</li>
 *   <li>POST /api/purchase/orders/{docNo}/close → 200 关闭（APPROVED→EXECUTING→COMPLETED）；</li>
 *   <li>GET  /api/purchase/orders/{docNo} → 200 单据详情（不存在 404）；</li>
 *   <li>GET  /api/purchase/orders?supplierId=&status=&page=&size= → 200 分页。</li>
 * </ul>
 *
 * <p>权限（docs/权限矩阵.md）：写/查均须 {@code purchase:order}（ADMIN/BOSS/PURCHASER）。
 * 错误契约见 {@link PurchaseExceptionHandler}：单据/供应商/商品不存在 404、非法状态流转 409、
 * 业务/参数不合法 400，错误体一律 {"error": "..."}。
 */
@RestController
@RequestMapping("/api/purchase/orders")
@PreAuthorize("@perm.has('purchase:order')")
public class PurchaseOrderController {

    private final PurchaseOrderAppService purchaseOrderAppService;

    public PurchaseOrderController(PurchaseOrderAppService purchaseOrderAppService) {
        this.purchaseOrderAppService = purchaseOrderAppService;
    }

    /** 建单（草稿，自动编号） */
    @PostMapping
    public ResponseEntity<PurchaseOrderResponse> create(@Valid @RequestBody CreatePurchaseOrderRequest request) {
        PurchaseOrderResponse body = PurchaseOrderResponse.from(purchaseOrderAppService.create(
                request.supplierId(), request.orderDate(), request.remark(),
                request.lines(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 审核（DRAFT → APPROVED） */
    @PostMapping("/{docNo}/approve")
    public PurchaseOrderResponse approve(@PathVariable String docNo) {
        return PurchaseOrderResponse.from(purchaseOrderAppService.approve(docNo, CurrentUser.operator()));
    }

    /** 关闭（APPROVED → EXECUTING → COMPLETED，自此不再收货） */
    @PostMapping("/{docNo}/close")
    public PurchaseOrderResponse close(@PathVariable String docNo) {
        return PurchaseOrderResponse.from(purchaseOrderAppService.close(docNo, CurrentUser.operator()));
    }

    /** 单据详情（不存在 404） */
    @GetMapping("/{docNo}")
    public PurchaseOrderResponse get(@PathVariable String docNo) {
        return PurchaseOrderResponse.from(purchaseOrderAppService.get(docNo));
    }

    /** 分页（supplierId、status 可选；按创建倒序） */
    @GetMapping
    public PageResponse<PurchaseOrderResponse> search(
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.fromOrders(purchaseOrderAppService.search(
                new PurchaseOrderQuery(supplierId, parseStatus(status), page, size)));
    }

    /** 状态过滤解析（非法值给友好 400，不透出枚举内部异常） */
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
