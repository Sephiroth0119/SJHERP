package com.sjherp.app.collection;

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

import com.sjherp.app.collection.CollectionDtos.CollectionReceiptResponse;
import com.sjherp.app.collection.CollectionDtos.CreateCollectionReceiptRequest;
import com.sjherp.app.collection.CollectionDtos.PageResponse;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.collection.CollectionReceiptQuery;
import com.sjherp.domain.common.DocumentStatus;

import jakarta.validation.Valid;

/**
 * 收款单 API（M4-T04b）：
 * <ul>
 *   <li>POST /api/collections → 201 建单（分摊本次收款到若干应收，自动 RCPT- 编号）；</li>
 *   <li>POST /api/collections/{docNo}/approve → 200 审核（DRAFT→APPROVED）；</li>
 *   <li>POST /api/collections/{docNo}/post → 200 过账（核销应收 + 现金侧凭证，原子事务）；</li>
 *   <li>GET  /api/collections/{docNo} → 200 单据详情（不存在 404）；</li>
 *   <li>GET  /api/collections?customerId=&paymentAccountId=&status=&page=&size= → 200 分页。</li>
 * </ul>
 *
 * <p>权限：写/查均须 {@code finance:settlement}（复用 M4-T03 预留的核销写权限——收款单驱动核销，
 * 本就是 finance:settlement 的写入口，设计真源 §2.5/§6.4，无新增权限点）。
 */
@RestController
@RequestMapping("/api/collections")
@PreAuthorize("@perm.has('finance:settlement')")
public class CollectionReceiptController {

    private final CollectionReceiptAppService collectionReceiptAppService;

    public CollectionReceiptController(CollectionReceiptAppService collectionReceiptAppService) {
        this.collectionReceiptAppService = collectionReceiptAppService;
    }

    /** 建单（草稿，自动编号） */
    @PostMapping
    public ResponseEntity<CollectionReceiptResponse> create(
            @Valid @RequestBody CreateCollectionReceiptRequest request) {
        CollectionReceiptResponse body = CollectionReceiptResponse.from(
                collectionReceiptAppService.create(request.customerId(), request.paymentAccountId(),
                        request.receiptDate(), request.remark(), request.lines(),
                        CurrentUser.operator()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 审核（DRAFT → APPROVED） */
    @PostMapping("/{docNo}/approve")
    public CollectionReceiptResponse approve(@PathVariable String docNo) {
        return CollectionReceiptResponse.from(
                collectionReceiptAppService.approve(docNo, CurrentUser.operator()));
    }

    /** 过账（核销应收 + 现金侧凭证，原子事务） */
    @PostMapping("/{docNo}/post")
    public CollectionReceiptResponse post(@PathVariable String docNo) {
        return CollectionReceiptResponse.from(
                collectionReceiptAppService.post(docNo, CurrentUser.operator()));
    }

    /**
     * 冲销（红字单，M4-T07c，COMPLETED → REVERSED，不可逆）：反向核销应收（按收款单号反查正向核销记录
     * 逐条冲回）+ 红冲现金侧凭证（借贷对调）；原单转「已冲销」。已冲销/未过账单不可冲销，账期已关账 → 409，
     * 单据不存在 → 404。这解锁已核销销售发票的红冲（先冲收款单→应收 settled 回 0→可冲发票）。
     */
    @PostMapping("/{docNo}/reverse")
    public CollectionReceiptResponse reverse(@PathVariable String docNo) {
        return CollectionReceiptResponse.from(
                collectionReceiptAppService.reverse(docNo, CurrentUser.operator()));
    }

    /** 单据详情（不存在 404） */
    @GetMapping("/{docNo}")
    public CollectionReceiptResponse get(@PathVariable String docNo) {
        return CollectionReceiptResponse.from(collectionReceiptAppService.get(docNo));
    }

    /** 分页（customerId、paymentAccountId、status 可选；按创建倒序） */
    @GetMapping
    public PageResponse<CollectionReceiptResponse> search(
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long paymentAccountId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.fromReceipts(collectionReceiptAppService.search(
                new CollectionReceiptQuery(customerId, paymentAccountId, parseStatus(status),
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
