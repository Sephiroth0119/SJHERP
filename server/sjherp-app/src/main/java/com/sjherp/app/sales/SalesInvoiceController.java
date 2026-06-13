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
import com.sjherp.app.sales.SalesDtos.SalesInvoiceCreateRequest;
import com.sjherp.app.sales.SalesDtos.SalesInvoiceResponse;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.sales.SalesInvoiceQuery;

import jakarta.validation.Valid;

/**
 * 销售发票 API（M3-T10）：
 * <ul>
 *   <li>POST /api/sales/invoices → 201 建单（引用某已过账出库单，自动 SINV- 编号）；</li>
 *   <li>POST /api/sales/invoices/{docNo}/approve → 200 审核（DRAFT→APPROVED）；</li>
 *   <li>POST /api/sales/invoices/{docNo}/post → 200 过账（生成应收 OPEN）；</li>
 *   <li>POST /api/sales/invoices/{docNo}/cancel → 200 作废（仅 DRAFT）；</li>
 *   <li>GET  /api/sales/invoices/{docNo} → 200 单据详情（不存在 404）；</li>
 *   <li>GET  /api/sales/invoices?customerId=&salesDeliveryNo=&status=&page=&size= → 200 分页。</li>
 * </ul>
 *
 * <p>权限（docs/权限矩阵.md）：写/查均须 {@code sales:invoice}（ADMIN/BOSS/SALES/ACCOUNTANT）。
 * 开票数量校验不超出库已发量；过账按发票金额生成应收账款（OPEN，核销 M4-T03）。
 */
@RestController
@RequestMapping("/api/sales/invoices")
@PreAuthorize("@perm.has('sales:invoice')")
public class SalesInvoiceController {

    private final SalesInvoiceAppService salesInvoiceAppService;

    public SalesInvoiceController(SalesInvoiceAppService salesInvoiceAppService) {
        this.salesInvoiceAppService = salesInvoiceAppService;
    }

    /** 建单（草稿，自动编号） */
    @PostMapping
    public ResponseEntity<SalesInvoiceResponse> create(@Valid @RequestBody SalesInvoiceCreateRequest request) {
        SalesInvoiceResponse body = SalesInvoiceResponse.from(salesInvoiceAppService.create(
                request.salesDeliveryNo(), request.invoiceDate(), request.dueDate(),
                request.remark(), request.lines(), CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 审核（DRAFT → APPROVED） */
    @PostMapping("/{docNo}/approve")
    public SalesInvoiceResponse approve(@PathVariable String docNo) {
        return SalesInvoiceResponse.from(salesInvoiceAppService.approve(docNo, CurrentUser.operator()));
    }

    /** 过账（APPROVED → EXECUTING → COMPLETED，生成应收 OPEN） */
    @PostMapping("/{docNo}/post")
    public SalesInvoiceResponse post(@PathVariable String docNo) {
        return SalesInvoiceResponse.from(salesInvoiceAppService.post(docNo, CurrentUser.operator()));
    }

    /** 作废（仅 DRAFT） */
    @PostMapping("/{docNo}/cancel")
    public SalesInvoiceResponse cancel(@PathVariable String docNo) {
        return SalesInvoiceResponse.from(salesInvoiceAppService.cancel(docNo, CurrentUser.operator()));
    }

    /** 单据详情（不存在 404） */
    @GetMapping("/{docNo}")
    public SalesInvoiceResponse get(@PathVariable String docNo) {
        return SalesInvoiceResponse.from(salesInvoiceAppService.get(docNo));
    }

    /** 分页（customerId、salesDeliveryNo、status 可选；按创建倒序） */
    @GetMapping
    public PageResponse<SalesInvoiceResponse> search(
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String salesDeliveryNo,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.ofInvoices(salesInvoiceAppService.search(
                new SalesInvoiceQuery(customerId, salesDeliveryNo, parseStatus(status), page, size)));
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
