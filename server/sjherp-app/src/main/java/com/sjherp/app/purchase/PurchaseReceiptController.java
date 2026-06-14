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

import com.sjherp.app.purchase.PurchaseDtos.CreatePurchaseReceiptRequest;
import com.sjherp.app.purchase.PurchaseDtos.PageResponse;
import com.sjherp.app.purchase.PurchaseDtos.PurchaseReceiptResponse;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.purchase.PurchaseReceiptQuery;

import jakarta.validation.Valid;

/**
 * 采购入库单 API（M3-T06）：
 * <ul>
 *   <li>POST /api/purchase/receipts → 201 建单（引用采购订单收货，自动 PR- 编号）；</li>
 *   <li>POST /api/purchase/receipts/{docNo}/approve → 200 审核（DRAFT→APPROVED）；</li>
 *   <li>POST /api/purchase/receipts/{docNo}/post → 200 过账（产生 PURCHASE_IN 入库流水 + 回写到货量）；</li>
 *   <li>GET  /api/purchase/receipts/{docNo} → 200 单据详情（不存在 404）；</li>
 *   <li>GET  /api/purchase/receipts?warehouseId=&purchaseOrderNo=&status=&page=&size= → 200 分页。</li>
 * </ul>
 *
 * <p>权限（docs/权限矩阵.md）：写/查均须 {@code purchase:receipt}（ADMIN/BOSS/PURCHASER/WAREHOUSE）。
 * 错误契约见 {@link PurchaseExceptionHandler}：单据/仓库不存在 404、非法状态流转 409、库存相关 400/409、
 * 业务/参数不合法（部分收货超量、引用订单未审核等）400。
 *
 * <p>过账唯一经 {@code PurchaseReceiptAppService}（外层事务）→ 领域 PurchaseReceiptService →
 * 库存唯一写入口 TransactionalInventoryService（CLAUDE.md 原则 1）。
 */
@RestController
@RequestMapping("/api/purchase/receipts")
@PreAuthorize("@perm.has('purchase:receipt')")
public class PurchaseReceiptController {

    private final PurchaseReceiptAppService purchaseReceiptAppService;

    public PurchaseReceiptController(PurchaseReceiptAppService purchaseReceiptAppService) {
        this.purchaseReceiptAppService = purchaseReceiptAppService;
    }

    /** 建单（草稿，自动编号） */
    @PostMapping
    public ResponseEntity<PurchaseReceiptResponse> create(
            @Valid @RequestBody CreatePurchaseReceiptRequest request) {
        PurchaseReceiptResponse body = PurchaseReceiptResponse.from(purchaseReceiptAppService.create(
                request.purchaseOrderNo(), request.warehouseId(), request.receiptDate(),
                request.remark(), request.lines(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 审核（DRAFT → APPROVED） */
    @PostMapping("/{docNo}/approve")
    public PurchaseReceiptResponse approve(@PathVariable String docNo) {
        return PurchaseReceiptResponse.from(purchaseReceiptAppService.approve(docNo, CurrentUser.operator()));
    }

    /** 过账（APPROVED → EXECUTING → COMPLETED，产生 PURCHASE_IN 入库流水 + 回写到货量） */
    @PostMapping("/{docNo}/post")
    public PurchaseReceiptResponse post(@PathVariable String docNo) {
        return PurchaseReceiptResponse.from(purchaseReceiptAppService.post(docNo, CurrentUser.operator()));
    }

    /**
     * 冲销（红字单，M4-T07b，COMPLETED → REVERSED）：反向库存（按原成本出库）、回退采购订单到货量、
     * 红冲入库自动凭证；不可逆。原单非 COMPLETED/已冲销 → 409，账期已关账 → 409，单据不存在 → 404。
     */
    @PostMapping("/{docNo}/reverse")
    public PurchaseReceiptResponse reverse(@PathVariable String docNo) {
        return PurchaseReceiptResponse.from(purchaseReceiptAppService.reverse(docNo, CurrentUser.operator()));
    }

    /** 单据详情（不存在 404） */
    @GetMapping("/{docNo}")
    public PurchaseReceiptResponse get(@PathVariable String docNo) {
        return PurchaseReceiptResponse.from(purchaseReceiptAppService.get(docNo));
    }

    /** 分页（warehouseId、purchaseOrderNo、status 可选；按创建倒序） */
    @GetMapping
    public PageResponse<PurchaseReceiptResponse> search(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String purchaseOrderNo,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.fromReceipts(purchaseReceiptAppService.search(
                new PurchaseReceiptQuery(warehouseId, blankToNull(purchaseOrderNo),
                        parseStatus(status), page, size)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
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
