package com.sjherp.app.sales;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.sjherp.app.sales.SalesOrderAppService.CreateResult;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesDeliveryLine;
import com.sjherp.domain.sales.SalesInvoice;
import com.sjherp.domain.sales.SalesInvoiceLine;
import com.sjherp.domain.sales.SalesOrder;
import com.sjherp.domain.sales.SalesOrderLine;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 销售线 API 的请求/响应 DTO（M3-T08/T09/T10）。
 *
 * <p>精度约定（CLAUDE.md 原则 5）：数量/金额/单价在 JSON 中一律以<b>字符串</b>承载
 * （BigDecimal#toPlainString），绝不用 JSON 数字——与库存/盘点/调拨 API 同口径。
 */
public final class SalesDtos {

    private SalesDtos() {
    }

    // ================================================================
    // T08 销售订单
    // ================================================================

    /** 销售订单建单请求 */
    public record SalesOrderCreateRequest(
            @NotNull(message = "客户 id 不能为空") Long customerId,
            LocalDate orderDate,
            String remark,
            /** 可用库存检查仓库 id（可空——空则不检查；不足仅警告不阻断下单） */
            Long checkWarehouseId,
            @NotEmpty(message = "销售订单至少要有一行") @Valid List<SalesOrderLineRequest> lines) {
    }

    /** 订单建单行输入：商品 id（必填）+ 数量（必填）+ 销售单价（必填） */
    public record SalesOrderLineRequest(
            @NotNull(message = "订单行商品 id 不能为空") Long productId,
            @NotNull(message = "订单数量不能为空") BigDecimal quantity,
            @NotNull(message = "销售单价不能为空") BigDecimal unitPrice) {
    }

    /** 订单建单响应（单据 + 可用库存不足警告） */
    public record SalesOrderCreateResponse(SalesOrderResponse order, List<String> warnings) {

        public static SalesOrderCreateResponse from(CreateResult result) {
            return new SalesOrderCreateResponse(SalesOrderResponse.from(result.order()),
                    result.warnings());
        }
    }

    /** 销售订单响应（单据头 + 行项目 + 总额） */
    public record SalesOrderResponse(String docNo, long customerId, String orderDate, String remark,
                                     String status, String totalAmount, List<SalesOrderLineResponse> lines) {

        public static SalesOrderResponse from(SalesOrder order) {
            List<SalesOrderLineResponse> lines = order.getLines().stream()
                    .map(SalesOrderLineResponse::from).toList();
            return new SalesOrderResponse(order.getDocNo(), order.getCustomerId(),
                    order.getOrderDate().toString(), order.getRemark(), order.getStatus().name(),
                    plain(order.totalAmount()), lines);
        }
    }

    /** 订单行响应（数量/单价/金额/累计发货量为字符串） */
    public record SalesOrderLineResponse(int lineNo, long productId, String quantity, String unitPrice,
                                         String amount, String deliveredQty, String remainingQty) {

        static SalesOrderLineResponse from(SalesOrderLine line) {
            return new SalesOrderLineResponse(line.getLineNo(), line.getProductId(),
                    plain(line.getQuantity()), plain(line.getUnitPrice()), plain(line.getAmount()),
                    plain(line.getDeliveredQty()), plain(line.remainingQty()));
        }
    }

    /**
     * 销售出库建单的订单窄读候选：只含可发货订单头与未发完行，不授予订单写能力。
     */
    public record SalesDeliveryOrderOptionResponse(String docNo, long customerId, String orderDate,
                                                   String remark, String status,
                                                   List<SalesDeliveryOrderOptionLineResponse> lines) {

        public static SalesDeliveryOrderOptionResponse from(SalesOrder order) {
            List<SalesDeliveryOrderOptionLineResponse> lines = order.getLines().stream()
                    .filter(line -> line.remainingQty().signum() > 0)
                    .map(SalesDeliveryOrderOptionLineResponse::from)
                    .toList();
            return new SalesDeliveryOrderOptionResponse(order.getDocNo(), order.getCustomerId(),
                    order.getOrderDate().toString(), order.getRemark(), order.getStatus().name(), lines);
        }

        public static boolean isDeliverable(SalesOrder order) {
            String status = order.getStatus().name();
            return ("APPROVED".equals(status) || "EXECUTING".equals(status))
                    && order.getLines().stream().anyMatch(line -> line.remainingQty().signum() > 0);
        }
    }

    /** 出库订单候选未发完行；数量与金额字段保持 BigDecimal 字符串。 */
    public record SalesDeliveryOrderOptionLineResponse(int soLineNo, long productId,
                                                       String quantity, String unitPrice,
                                                       String amount, String deliveredQty,
                                                       String remainingQty) {

        static SalesDeliveryOrderOptionLineResponse from(SalesOrderLine line) {
            return new SalesDeliveryOrderOptionLineResponse(line.getLineNo(), line.getProductId(),
                    plain(line.getQuantity()), plain(line.getUnitPrice()), plain(line.getAmount()),
                    plain(line.getDeliveredQty()), plain(line.remainingQty()));
        }
    }

    // ================================================================
    // T09 销售出库单
    // ================================================================

    /** 出库单建单请求 */
    public record SalesDeliveryCreateRequest(
            @NotNull(message = "关联销售订单号不能为空") String salesOrderNo,
            @NotNull(message = "出库仓库 id 不能为空") Long warehouseId,
            String remark,
            @NotEmpty(message = "销售出库单至少要有一行") @Valid List<SalesDeliveryLineRequest> lines) {
    }

    /** 出库建单行输入：关联订单行号 + 商品 + 发货数量 */
    public record SalesDeliveryLineRequest(
            @NotNull(message = "关联订单行号不能为空") Integer soLineNo,
            @NotNull(message = "出库行商品 id 不能为空") Long productId,
            @NotNull(message = "发货数量不能为空") BigDecimal quantity) {
    }

    /** 销售出库单响应（单据头 + 行项目 + 出库总成本） */
    public record SalesDeliveryResponse(String docNo, String salesOrderNo, long warehouseId,
                                        String remark, String status, String totalCogs,
                                        List<SalesDeliveryLineResponse> lines) {

        public static SalesDeliveryResponse from(SalesDelivery delivery) {
            List<SalesDeliveryLineResponse> lines = delivery.getLines().stream()
                    .map(SalesDeliveryLineResponse::from).toList();
            return new SalesDeliveryResponse(delivery.getDocNo(), delivery.getSalesOrderNo(),
                    delivery.getWarehouseId(), delivery.getRemark(), delivery.getStatus().name(),
                    plain(delivery.totalCogs()), lines);
        }
    }

    /** 出库行响应（发货数量与 COGS 为字符串，COGS 未过账时为 null） */
    public record SalesDeliveryLineResponse(int lineNo, int soLineNo, long productId, String quantity,
                                            String cogsAmount) {

        static SalesDeliveryLineResponse from(SalesDeliveryLine line) {
            return new SalesDeliveryLineResponse(line.getLineNo(), line.getSoLineNo(),
                    line.getProductId(), plain(line.getQuantity()), plain(line.getCogsAmount()));
        }
    }

    /**
     * 销售发票建单的已过账出库单窄读投影。
     *
     * <p>只在 {@code sales:invoice} 权限边界内返回，且只保留仍有可开票数量的行。
     */
    public record SalesInvoiceDeliveryOptionResponse(
            String docNo, String salesOrderNo, long warehouseId, String remark, String status,
            List<SalesInvoiceDeliveryLineOptionResponse> lines) {

        public static boolean isInvoiceable(SalesDelivery delivery) {
            return delivery.getStatus() == DocumentStatus.COMPLETED
                    && delivery.getLines().stream()
                    .anyMatch(line -> line.outstandingInvoiceableQty().signum() > 0);
        }

        public static SalesInvoiceDeliveryOptionResponse from(SalesDelivery delivery) {
            List<SalesInvoiceDeliveryLineOptionResponse> lines = delivery.getLines().stream()
                    .filter(line -> line.outstandingInvoiceableQty().signum() > 0)
                    .map(SalesInvoiceDeliveryLineOptionResponse::from)
                    .toList();
            return new SalesInvoiceDeliveryOptionResponse(
                    delivery.getDocNo(), delivery.getSalesOrderNo(), delivery.getWarehouseId(),
                    delivery.getRemark(), delivery.getStatus().name(), lines);
        }
    }

    /** 出库单未开完行投影；数量、已开票量和剩余量仍按 BigDecimal 字符串承载。 */
    public record SalesInvoiceDeliveryLineOptionResponse(
            int deliveryLineNo, long productId, String quantity, String invoicedQty,
            String outstandingInvoiceableQty) {

        static SalesInvoiceDeliveryLineOptionResponse from(SalesDeliveryLine line) {
            return new SalesInvoiceDeliveryLineOptionResponse(
                    line.getLineNo(), line.getProductId(), plain(line.getQuantity()), plain(line.getInvoicedQty()),
                    plain(line.outstandingInvoiceableQty()));
        }
    }

    // ================================================================
    // T10 销售发票
    // ================================================================

    /** 发票建单请求（客户从出库单链路推导，无须传） */
    public record SalesInvoiceCreateRequest(
            @NotNull(message = "关联出库单号不能为空") String salesDeliveryNo,
            LocalDate invoiceDate,
            LocalDate dueDate,
            String remark,
            @NotEmpty(message = "销售发票至少要有一行") @Valid List<SalesInvoiceLineRequest> lines) {
    }

    /** 发票建单行输入：关联出库行号 + 商品 + 开票数量 + 单价 */
    public record SalesInvoiceLineRequest(
            @NotNull(message = "关联出库行号不能为空") Integer deliveryLineNo,
            @NotNull(message = "发票行商品 id 不能为空") Long productId,
            @NotNull(message = "开票数量不能为空") BigDecimal quantity,
            @NotNull(message = "开票单价不能为空") BigDecimal unitPrice) {
    }

    /** 销售发票响应（单据头 + 行项目 + 总额） */
    public record SalesInvoiceResponse(String docNo, String salesDeliveryNo, long customerId,
                                       String invoiceDate, String dueDate, String remark,
                                       String status, String totalAmount,
                                       List<SalesInvoiceLineResponse> lines) {

        public static SalesInvoiceResponse from(SalesInvoice invoice) {
            List<SalesInvoiceLineResponse> lines = invoice.getLines().stream()
                    .map(SalesInvoiceLineResponse::from).toList();
            return new SalesInvoiceResponse(invoice.getDocNo(), invoice.getSalesDeliveryNo(),
                    invoice.getCustomerId(), invoice.getInvoiceDate().toString(),
                    invoice.getDueDate() == null ? null : invoice.getDueDate().toString(),
                    invoice.getRemark(), invoice.getStatus().name(), plain(invoice.totalAmount()), lines);
        }
    }

    /** 发票行响应（数量/单价/金额为字符串） */
    public record SalesInvoiceLineResponse(int lineNo, int deliveryLineNo, long productId,
                                           String quantity, String unitPrice, String amount) {

        static SalesInvoiceLineResponse from(SalesInvoiceLine line) {
            return new SalesInvoiceLineResponse(line.getLineNo(), line.getDeliveryLineNo(),
                    line.getProductId(), plain(line.getQuantity()), plain(line.getUnitPrice()),
                    plain(line.getAmount()));
        }
    }

    // ================================================================
    // 通用分页响应
    // ================================================================

    /** 分页响应（与库存/盘点/调拨 API 同构） */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {

        public static PageResponse<SalesOrderResponse> ofOrders(PageResult<SalesOrder> result) {
            return new PageResponse<>(result.items().stream().map(SalesOrderResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }

        public static PageResponse<SalesDeliveryOrderOptionResponse> ofDeliveryOrderOptions(
                PageResult<SalesOrder> result) {
            return new PageResponse<>(
                    result.items().stream().map(SalesDeliveryOrderOptionResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }

        public static PageResponse<SalesDeliveryResponse> ofDeliveries(PageResult<SalesDelivery> result) {
            return new PageResponse<>(result.items().stream().map(SalesDeliveryResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }

        public static PageResponse<SalesInvoiceDeliveryOptionResponse> ofInvoiceDeliveryOptions(
                PageResult<SalesDelivery> result) {
            return new PageResponse<>(
                    result.items().stream().map(SalesInvoiceDeliveryOptionResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }

        public static PageResponse<SalesInvoiceResponse> ofInvoices(PageResult<SalesInvoice> result) {
            return new PageResponse<>(result.items().stream().map(SalesInvoiceResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
