package com.sjherp.app.purchase;

import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.purchase.PurchaseDtos.PageResponse;
import com.sjherp.app.purchase.PurchaseDtos.PayableResponse;
import com.sjherp.domain.payable.AccountsPayableQuery;
import com.sjherp.domain.payable.PayableStatus;

/**
 * 应付账款查询 API（M3-T07）：GET /api/payables 分页列出应付账款。
 *
 * <p>权限：<b>登录即可</b>（无权限点，按「查询登录即可」通则——应付列表是只读台账查询，
 * 不像盘点/调拨那样是受控写动作）。错误契约见 {@link PurchaseExceptionHandler}。
 *
 * <p>应付由采购发票过账生成（{@code domain/payable}），本期数据状态恒 OPEN（核销 M4-T03）。
 */
@RestController
@RequestMapping("/api/payables")
public class PayableController {

    private final PurchaseInvoiceAppService purchaseInvoiceAppService;

    public PayableController(PurchaseInvoiceAppService purchaseInvoiceAppService) {
        this.purchaseInvoiceAppService = purchaseInvoiceAppService;
    }

    /** 分页（supplierId、status 可选；按生成倒序） */
    @GetMapping
    public PageResponse<PayableResponse> search(
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.fromPayables(purchaseInvoiceAppService.searchPayables(
                new AccountsPayableQuery(supplierId, parseStatus(status), page, size)));
    }

    /** 应付状态过滤解析（非法值给友好 400） */
    private static PayableStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return PayableStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status 非法（OPEN/PARTIAL/SETTLED）: " + status);
        }
    }
}
