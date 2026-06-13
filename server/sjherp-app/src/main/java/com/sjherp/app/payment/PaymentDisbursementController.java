package com.sjherp.app.payment;

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

import com.sjherp.app.payment.PaymentDtos.CreatePaymentDisbursementRequest;
import com.sjherp.app.payment.PaymentDtos.PageResponse;
import com.sjherp.app.payment.PaymentDtos.PaymentDisbursementResponse;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.payment.PaymentDisbursementQuery;

import jakarta.validation.Valid;

/**
 * 付款单 API（M4-T04b，与收款单 {@code CollectionReceiptController} 对称）：
 * <ul>
 *   <li>POST /api/payments → 201 建单（分摊本次付款到若干应付，自动 PAYV- 编号）；</li>
 *   <li>POST /api/payments/{docNo}/approve → 200 审核（DRAFT→APPROVED）；</li>
 *   <li>POST /api/payments/{docNo}/post → 200 过账（核销应付 + 现金侧凭证，原子事务）；</li>
 *   <li>GET  /api/payments/{docNo} → 200 单据详情（不存在 404）；</li>
 *   <li>GET  /api/payments?supplierId=&paymentAccountId=&status=&page=&size= → 200 分页。</li>
 * </ul>
 *
 * <p>权限：写/查均须 {@code finance:settlement}（复用 M4-T03 预留的核销写权限——付款单驱动核销，
 * 本就是 finance:settlement 的写入口，设计真源 §2.5/§6.4，无新增权限点）。
 */
@RestController
@RequestMapping("/api/payments")
@PreAuthorize("@perm.has('finance:settlement')")
public class PaymentDisbursementController {

    private final PaymentDisbursementAppService paymentDisbursementAppService;

    public PaymentDisbursementController(PaymentDisbursementAppService paymentDisbursementAppService) {
        this.paymentDisbursementAppService = paymentDisbursementAppService;
    }

    /** 建单（草稿，自动编号） */
    @PostMapping
    public ResponseEntity<PaymentDisbursementResponse> create(
            @Valid @RequestBody CreatePaymentDisbursementRequest request) {
        PaymentDisbursementResponse body = PaymentDisbursementResponse.from(
                paymentDisbursementAppService.create(request.supplierId(), request.paymentAccountId(),
                        request.paymentDate(), request.remark(), request.lines(),
                        CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 审核（DRAFT → APPROVED） */
    @PostMapping("/{docNo}/approve")
    public PaymentDisbursementResponse approve(@PathVariable String docNo) {
        return PaymentDisbursementResponse.from(
                paymentDisbursementAppService.approve(docNo, CurrentUser.operator()));
    }

    /** 过账（核销应付 + 现金侧凭证，原子事务） */
    @PostMapping("/{docNo}/post")
    public PaymentDisbursementResponse post(@PathVariable String docNo) {
        return PaymentDisbursementResponse.from(
                paymentDisbursementAppService.post(docNo, CurrentUser.operator()));
    }

    /** 单据详情（不存在 404） */
    @GetMapping("/{docNo}")
    public PaymentDisbursementResponse get(@PathVariable String docNo) {
        return PaymentDisbursementResponse.from(paymentDisbursementAppService.get(docNo));
    }

    /** 分页（supplierId、paymentAccountId、status 可选；按创建倒序） */
    @GetMapping
    public PageResponse<PaymentDisbursementResponse> search(
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) Long paymentAccountId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.fromDisbursements(paymentDisbursementAppService.search(
                new PaymentDisbursementQuery(supplierId, paymentAccountId, parseStatus(status),
                        page, size)));
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
