package com.sjherp.app.purchase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.payable.AccountsPayable;
import com.sjherp.domain.purchase.PurchaseInvoice;
import com.sjherp.domain.purchase.PurchaseInvoiceLine;
import com.sjherp.domain.purchase.PurchaseOrder;
import com.sjherp.domain.purchase.PurchaseOrderLine;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.purchase.PurchaseReceiptLine;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 采购线（采购订单 / 采购入库单 / 采购发票 / 应付）API 的请求/响应 DTO（M3-T05/T06/T07）。
 *
 * <p>精度约定（CLAUDE.md 原则 5）：数量/金额在 JSON 中一律以<b>字符串</b>承载
 * （BigDecimal#toPlainString），绝不用 JSON 数字——与库存/盘点/调拨 API 同口径。
 */
public final class PurchaseDtos {

    private PurchaseDtos() {
    }

    // =============================================================== T05 采购订单

    /** 建单请求：供应商 + 下单日期（可空默认今天）+ 行数组 */
    public record CreatePurchaseOrderRequest(
            @NotNull(message = "供应商 id 不能为空") Long supplierId,
            LocalDate orderDate,
            String remark,
            @NotEmpty(message = "采购订单至少要有一行") @Valid List<PurchaseOrderLineRequest> lines) {
    }

    /** 建单行输入：商品 id（必填）+ 订购数量 + 采购单价（业务校验在领域层） */
    public record PurchaseOrderLineRequest(
            @NotNull(message = "采购订单行商品 id 不能为空") Long productId,
            @NotNull(message = "订购数量不能为空") BigDecimal quantity,
            @NotNull(message = "采购单价不能为空") BigDecimal unitPrice) {
    }

    /** 采购订单响应（单据头 + 行项目） */
    public record PurchaseOrderResponse(String docNo, long supplierId, LocalDate orderDate,
                                        String remark, String status, String totalAmount,
                                        List<PurchaseOrderLineResponse> lines) {

        public static PurchaseOrderResponse from(PurchaseOrder order) {
            List<PurchaseOrderLineResponse> lines = order.getLines().stream()
                    .map(PurchaseOrderLineResponse::from).toList();
            return new PurchaseOrderResponse(order.getDocNo(), order.getSupplierId(),
                    order.getOrderDate(), order.getRemark(), order.getStatus().name(),
                    plain(order.totalAmount()), lines);
        }
    }

    /** 采购订单行响应：商品 + 数量/单价/金额 + 已到货量/未到货量（数量金额为字符串） */
    public record PurchaseOrderLineResponse(int lineNo, long productId, String quantity,
                                            String unitPrice, String amount, String receivedQty,
                                            String outstandingQty) {

        static PurchaseOrderLineResponse from(PurchaseOrderLine line) {
            return new PurchaseOrderLineResponse(line.getLineNo(), line.getProductId(),
                    plain(line.getQuantity()), plain(line.getUnitPrice()), plain(line.getAmount()),
                    plain(line.getReceivedQty()), plain(line.outstandingQty()));
        }
    }

    // =============================================================== T06 采购入库单

    /** 建单请求：引用采购订单号 + 收货仓库 + 收货日期（可空默认今天）+ 行数组 */
    public record CreatePurchaseReceiptRequest(
            @NotNull(message = "引用的采购订单号不能为空") String purchaseOrderNo,
            @NotNull(message = "收货仓库 id 不能为空") Long warehouseId,
            LocalDate receiptDate,
            String remark,
            @NotEmpty(message = "采购入库单至少要有一行") @Valid List<PurchaseReceiptLineRequest> lines) {
    }

    /** 建单行输入：引用采购订单行号（必填）+ 收货数量 + 收货单价（可空取采购订单行单价） */
    public record PurchaseReceiptLineRequest(
            @NotNull(message = "收货行引用的采购订单行号不能为空") Integer poLineNo,
            @NotNull(message = "收货数量不能为空") BigDecimal quantity,
            BigDecimal unitCost) {
    }

    /** 采购入库单响应（单据头 + 行项目） */
    public record PurchaseReceiptResponse(String docNo, String purchaseOrderNo, long warehouseId,
                                          LocalDate receiptDate, String remark, String status,
                                          String totalAmount, List<PurchaseReceiptLineResponse> lines) {

        public static PurchaseReceiptResponse from(PurchaseReceipt receipt) {
            List<PurchaseReceiptLineResponse> lines = receipt.getLines().stream()
                    .map(PurchaseReceiptLineResponse::from).toList();
            return new PurchaseReceiptResponse(receipt.getDocNo(), receipt.getPurchaseOrderNo(),
                    receipt.getWarehouseId(), receipt.getReceiptDate(), receipt.getRemark(),
                    receipt.getStatus().name(), plain(receipt.totalAmount()), lines);
        }
    }

    /** 采购入库单行响应：引用采购订单行 + 商品 + 收货数量/单价/金额 */
    public record PurchaseReceiptLineResponse(int lineNo, int poLineNo, long productId,
                                              String quantity, String unitCost, String amount) {

        static PurchaseReceiptLineResponse from(PurchaseReceiptLine line) {
            return new PurchaseReceiptLineResponse(line.getLineNo(), line.getPoLineNo(),
                    line.getProductId(), plain(line.getQuantity()), plain(line.getUnitCost()),
                    plain(line.getAmount()));
        }
    }

    // =============================================================== T07 采购发票

    /** 建单请求：引用采购入库单号 + 发票日期（可空默认今天）+ 供应商发票号（可空）+ 行数组 */
    public record CreatePurchaseInvoiceRequest(
            @NotNull(message = "引用的采购入库单号不能为空") String purchaseReceiptNo,
            LocalDate invoiceDate,
            String supplierInvoiceNo,
            String remark,
            @NotEmpty(message = "采购发票至少要有一行") @Valid List<PurchaseInvoiceLineRequest> lines) {
    }

    /** 建单行输入：引用采购入库单行号（必填）+ 开票数量 + 开票金额 */
    public record PurchaseInvoiceLineRequest(
            @NotNull(message = "发票行引用的采购入库单行号不能为空") Integer receiptLineNo,
            @NotNull(message = "开票数量不能为空") BigDecimal quantity,
            @NotNull(message = "开票金额不能为空") BigDecimal amount) {
    }

    /** 采购发票响应（单据头 + 行项目） */
    public record PurchaseInvoiceResponse(String docNo, String purchaseReceiptNo, long supplierId,
                                          LocalDate invoiceDate, String supplierInvoiceNo, String remark,
                                          String status, String totalAmount,
                                          List<PurchaseInvoiceLineResponse> lines) {

        public static PurchaseInvoiceResponse from(PurchaseInvoice invoice) {
            List<PurchaseInvoiceLineResponse> lines = invoice.getLines().stream()
                    .map(PurchaseInvoiceLineResponse::from).toList();
            return new PurchaseInvoiceResponse(invoice.getDocNo(), invoice.getPurchaseReceiptNo(),
                    invoice.getSupplierId(), invoice.getInvoiceDate(), invoice.getSupplierInvoiceNo(),
                    invoice.getRemark(), invoice.getStatus().name(), plain(invoice.totalAmount()), lines);
        }
    }

    /** 采购发票行响应：引用收货行 + 商品 + 开票数量/金额 */
    public record PurchaseInvoiceLineResponse(int lineNo, int receiptLineNo, long productId,
                                              String quantity, String amount) {

        static PurchaseInvoiceLineResponse from(PurchaseInvoiceLine line) {
            return new PurchaseInvoiceLineResponse(line.getLineNo(), line.getReceiptLineNo(),
                    line.getProductId(), plain(line.getQuantity()), plain(line.getAmount()));
        }
    }

    // =============================================================== 应付账款

    /** 应付账款响应（来源单据/供应商/金额/到期日/状态） */
    public record PayableResponse(long id, long supplierId, String amount, String sourceDocNo,
                                  LocalDate dueDate, String status, String settledAmount,
                                  String outstandingAmount) {

        public static PayableResponse from(AccountsPayable payable) {
            return new PayableResponse(payable.getId() == null ? 0L : payable.getId(),
                    payable.getSupplierId(), plain(payable.getAmount()), payable.getSourceDocNo(),
                    payable.getDueDate(), payable.getStatus().name(), plain(payable.getSettledAmount()),
                    plain(payable.outstandingAmount()));
        }
    }

    // =============================================================== 分页

    /** 分页响应（与库存/盘点/调拨 API 同构） */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {

        static PageResponse<PurchaseOrderResponse> fromOrders(PageResult<PurchaseOrder> result) {
            return new PageResponse<>(
                    result.items().stream().map(PurchaseOrderResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }

        static PageResponse<PurchaseReceiptResponse> fromReceipts(PageResult<PurchaseReceipt> result) {
            return new PageResponse<>(
                    result.items().stream().map(PurchaseReceiptResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }

        static PageResponse<PurchaseInvoiceResponse> fromInvoices(PageResult<PurchaseInvoice> result) {
            return new PageResponse<>(
                    result.items().stream().map(PurchaseInvoiceResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }

        static PageResponse<PayableResponse> fromPayables(PageResult<AccountsPayable> result) {
            return new PageResponse<>(
                    result.items().stream().map(PayableResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
