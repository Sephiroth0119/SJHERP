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

import com.sjherp.app.purchase.PurchaseDtos.CreatePurchaseInvoiceRequest;
import com.sjherp.app.purchase.PurchaseDtos.PageResponse;
import com.sjherp.app.purchase.PurchaseDtos.PurchaseInvoiceResponse;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.purchase.PurchaseInvoiceQuery;

import jakarta.validation.Valid;

/**
 * 采购发票 API（M3-T07）：
 * <ul>
 *   <li>POST /api/purchase/invoices → 201 建单（引用采购入库单，自动 PINV- 编号）；</li>
 *   <li>POST /api/purchase/invoices/{docNo}/approve → 200 审核（DRAFT→APPROVED）；</li>
 *   <li>POST /api/purchase/invoices/{docNo}/post → 200 过账（生成应付账款）；</li>
 *   <li>GET  /api/purchase/invoices/{docNo} → 200 单据详情（不存在 404）；</li>
 *   <li>GET  /api/purchase/invoices?supplierId=&purchaseReceiptNo=&status=&page=&size= → 200 分页。</li>
 * </ul>
 *
 * <p>权限（docs/权限矩阵.md）：写/查均须 {@code purchase:invoice}（ADMIN/BOSS/PURCHASER/ACCOUNTANT）。
 * 错误契约见 {@link PurchaseExceptionHandler}：单据不存在 404、非法状态流转 409、
 * 三单匹配超额/引用收货单未过账等 400。
 */
@RestController
@RequestMapping("/api/purchase/invoices")
@PreAuthorize("@perm.has('purchase:invoice')")
public class PurchaseInvoiceController {

    private final PurchaseInvoiceAppService purchaseInvoiceAppService;

    public PurchaseInvoiceController(PurchaseInvoiceAppService purchaseInvoiceAppService) {
        this.purchaseInvoiceAppService = purchaseInvoiceAppService;
    }

    /** 建单（草稿，自动编号） */
    @PostMapping
    public ResponseEntity<PurchaseInvoiceResponse> create(
            @Valid @RequestBody CreatePurchaseInvoiceRequest request) {
        PurchaseInvoiceResponse body = PurchaseInvoiceResponse.from(purchaseInvoiceAppService.create(
                request.purchaseReceiptNo(), request.invoiceDate(), request.supplierInvoiceNo(),
                request.remark(), request.lines(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 审核（DRAFT → APPROVED） */
    @PostMapping("/{docNo}/approve")
    public PurchaseInvoiceResponse approve(@PathVariable String docNo) {
        return PurchaseInvoiceResponse.from(purchaseInvoiceAppService.approve(docNo, CurrentUser.operator()));
    }

    /** 过账（APPROVED → EXECUTING → COMPLETED，生成应付账款） */
    @PostMapping("/{docNo}/post")
    public PurchaseInvoiceResponse post(@PathVariable String docNo) {
        return PurchaseInvoiceResponse.from(purchaseInvoiceAppService.post(docNo, CurrentUser.operator()));
    }

    /** 单据详情（不存在 404） */
    @GetMapping("/{docNo}")
    public PurchaseInvoiceResponse get(@PathVariable String docNo) {
        return PurchaseInvoiceResponse.from(purchaseInvoiceAppService.get(docNo));
    }

    /** 分页（supplierId、purchaseReceiptNo、status 可选；按创建倒序） */
    @GetMapping
    public PageResponse<PurchaseInvoiceResponse> search(
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) String purchaseReceiptNo,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.fromInvoices(purchaseInvoiceAppService.search(
                new PurchaseInvoiceQuery(supplierId, blankToNull(purchaseReceiptNo),
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
