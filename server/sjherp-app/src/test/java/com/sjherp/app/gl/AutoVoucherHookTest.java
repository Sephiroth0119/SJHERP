package com.sjherp.app.gl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.sjherp.app.purchase.PurchaseInvoiceAppService;
import com.sjherp.app.purchase.PurchaseReceiptAppService;
import com.sjherp.app.sales.SalesDeliveryAppService;
import com.sjherp.app.sales.SalesInvoiceAppService;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.domain.partner.Supplier;
import com.sjherp.domain.partner.SupplierService;
import com.sjherp.domain.payable.AccountsPayableRepository;
import com.sjherp.domain.purchase.PurchaseInvoice;
import com.sjherp.domain.purchase.PurchaseInvoiceService;
import com.sjherp.domain.purchase.PurchaseOrderService;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.purchase.PurchaseReceiptService;
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesDeliveryService;
import com.sjherp.domain.sales.SalesInvoice;
import com.sjherp.domain.sales.SalesInvoiceService;
import com.sjherp.domain.sales.SalesOrderService;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 四个 *AppService.post 自动凭证钩子单测（M4-T02，拆解 §4/§7）。
 *
 * <p>每个 {@code post}：mock 领域 post 服务返回过账聚合 + mock {@link AutoVoucherService}，
 * {@code verify} 调用了对应的 {@code generateForXxx} 且传入的正是<b>领域 post 返回的同一聚合</b>
 * （销售出库尤其重要——此时 totalCogs 已回填）。
 *
 * <p>目的：把"过账后同事务直调自动凭证"这一行钩子锁进回归测试，防被误删（拆解 §4 否决事件方案，
 * 直调保证原子性 + 财务凭证强一致，钩子缺失即漏记凭证）。
 */
class AutoVoucherHookTest {

    private static final String OPERATOR = "tester";

    // ------------------------------------------------------------------
    // 采购入库：post → purchaseReceiptService.post → generateForPurchaseReceipt(返回的 receipt)
    // ------------------------------------------------------------------

    @Test
    void 采购入库post后调generateForPurchaseReceipt并传入领域返回聚合() {
        PurchaseReceiptService receiptService = mock(PurchaseReceiptService.class);
        WarehouseService warehouseService = mock(WarehouseService.class);
        DocumentNumberGenerator numberGenerator = mock(DocumentNumberGenerator.class);
        AutoVoucherService autoVoucherService = mock(AutoVoucherService.class);
        PurchaseReceiptAppService appService = new PurchaseReceiptAppService(
                receiptService, warehouseService, numberGenerator, autoVoucherService);

        PurchaseReceipt posted = mock(PurchaseReceipt.class);
        when(receiptService.post("PR-202606-0001", OPERATOR)).thenReturn(posted);

        PurchaseReceipt result = appService.post("PR-202606-0001", OPERATOR);

        assertThat(result).isSameAs(posted);
        verify(receiptService).post("PR-202606-0001", OPERATOR);
        // 钩子必须以领域 post 返回的同一聚合调用（同一实例引用）
        verify(autoVoucherService).generateForPurchaseReceipt(posted, OPERATOR);
    }

    // ------------------------------------------------------------------
    // 采购发票：post → purchaseInvoiceService.post → generateForPurchaseInvoice(返回的 posted)
    // ------------------------------------------------------------------

    @Test
    void 采购发票post后调generateForPurchaseInvoice并传入领域返回聚合() {
        PurchaseInvoiceService invoiceService = mock(PurchaseInvoiceService.class);
        PurchaseReceiptService receiptService = mock(PurchaseReceiptService.class);
        PurchaseOrderService orderService = mock(PurchaseOrderService.class);
        SupplierService supplierService = mock(SupplierService.class);
        AccountsPayableRepository payableRepository = mock(AccountsPayableRepository.class);
        DocumentNumberGenerator numberGenerator = mock(DocumentNumberGenerator.class);
        AutoVoucherService autoVoucherService = mock(AutoVoucherService.class);
        PurchaseInvoiceAppService appService = new PurchaseInvoiceAppService(invoiceService,
                receiptService, orderService, supplierService, payableRepository, numberGenerator,
                autoVoucherService);

        PurchaseInvoice existing = mock(PurchaseInvoice.class);
        when(existing.getSupplierId()).thenReturn(9L);
        when(invoiceService.get("PINV-202606-0001")).thenReturn(existing);
        Supplier supplier = mock(Supplier.class);
        when(supplier.getSettlementMethod()).thenReturn(SettlementMethod.CASH);
        when(supplierService.get(9L)).thenReturn(supplier);
        PurchaseInvoice posted = mock(PurchaseInvoice.class);
        when(invoiceService.post("PINV-202606-0001", SettlementMethod.CASH, OPERATOR))
                .thenReturn(posted);

        PurchaseInvoice result = appService.post("PINV-202606-0001", OPERATOR);

        assertThat(result).isSameAs(posted);
        verify(invoiceService).post("PINV-202606-0001", SettlementMethod.CASH, OPERATOR);
        // 钩子须以 post 返回的 posted 调用（非 get 取出的 existing）
        verify(autoVoucherService).generateForPurchaseInvoice(posted, OPERATOR);
    }

    // ------------------------------------------------------------------
    // 销售出库：post → salesDeliveryService.post → generateForSalesDelivery(返回的 delivery，COGS 已回填)
    // ------------------------------------------------------------------

    @Test
    void 销售出库post后调generateForSalesDelivery并传入领域返回聚合() {
        SalesDeliveryService deliveryService = mock(SalesDeliveryService.class);
        WarehouseService warehouseService = mock(WarehouseService.class);
        DocumentNumberGenerator numberGenerator = mock(DocumentNumberGenerator.class);
        AutoVoucherService autoVoucherService = mock(AutoVoucherService.class);
        SalesDeliveryAppService appService = new SalesDeliveryAppService(
                deliveryService, warehouseService, numberGenerator, autoVoucherService);

        SalesDelivery posted = mock(SalesDelivery.class);
        when(deliveryService.post("SD-202606-0001", OPERATOR)).thenReturn(posted);

        SalesDelivery result = appService.post("SD-202606-0001", OPERATOR);

        assertThat(result).isSameAs(posted);
        verify(deliveryService).post("SD-202606-0001", OPERATOR);
        // 钩子须传入 post 返回的 delivery（此时 totalCogs 已回填，凭证金额才正确）
        verify(autoVoucherService).generateForSalesDelivery(posted, OPERATOR);
    }

    // ------------------------------------------------------------------
    // 销售发票：post → salesInvoiceService.post → generateForSalesInvoice(返回的 invoice)
    // ------------------------------------------------------------------

    @Test
    void 销售发票post后调generateForSalesInvoice并传入领域返回聚合() {
        SalesInvoiceService invoiceService = mock(SalesInvoiceService.class);
        SalesDeliveryService deliveryService = mock(SalesDeliveryService.class);
        SalesOrderService orderService = mock(SalesOrderService.class);
        DocumentNumberGenerator numberGenerator = mock(DocumentNumberGenerator.class);
        AutoVoucherService autoVoucherService = mock(AutoVoucherService.class);
        SalesInvoiceAppService appService = new SalesInvoiceAppService(
                invoiceService, deliveryService, orderService, numberGenerator, autoVoucherService);

        SalesInvoice posted = mock(SalesInvoice.class);
        when(invoiceService.post("SINV-202606-0001", OPERATOR)).thenReturn(posted);

        SalesInvoice result = appService.post("SINV-202606-0001", OPERATOR);

        assertThat(result).isSameAs(posted);
        verify(invoiceService).post("SINV-202606-0001", OPERATOR);
        verify(autoVoucherService).generateForSalesInvoice(posted, OPERATOR);
    }

    // ------------------------------------------------------------------
    // 防御：钩子在领域 post 之后调用（业务过账失败则不应生成凭证）
    // ------------------------------------------------------------------

    @Test
    void 采购入库领域post抛异常_凭证钩子不被调用() {
        PurchaseReceiptService receiptService = mock(PurchaseReceiptService.class);
        WarehouseService warehouseService = mock(WarehouseService.class);
        DocumentNumberGenerator numberGenerator = mock(DocumentNumberGenerator.class);
        AutoVoucherService autoVoucherService = mock(AutoVoucherService.class);
        PurchaseReceiptAppService appService = new PurchaseReceiptAppService(
                receiptService, warehouseService, numberGenerator, autoVoucherService);

        when(receiptService.post("PR-202606-0001", OPERATOR))
                .thenThrow(new IllegalStateException("已过账，不可重复过账"));

        try {
            appService.post("PR-202606-0001", OPERATOR);
        } catch (IllegalStateException expected) {
            // 业务过账失败外抛
        }
        // 业务过账未成功 → 凭证钩子绝不应被调用（否则脏凭证）
        verify(autoVoucherService, org.mockito.Mockito.never())
                .generateForPurchaseReceipt(any(), eq(OPERATOR));
    }
}
