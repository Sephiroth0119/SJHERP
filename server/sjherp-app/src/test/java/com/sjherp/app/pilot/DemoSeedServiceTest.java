package com.sjherp.app.pilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.sjherp.app.collection.CollectionReceiptAppService;
import com.sjherp.app.gl.GlDtos.PeriodCloseReadiness;
import com.sjherp.app.gl.PeriodCloseService;
import com.sjherp.app.inventory.InventoryAdjustmentService;
import com.sjherp.app.payment.PaymentDisbursementAppService;
import com.sjherp.app.purchase.PurchaseInvoiceAppService;
import com.sjherp.app.purchase.PurchaseOrderAppService;
import com.sjherp.app.purchase.PurchaseReceiptAppService;
import com.sjherp.app.sales.SalesDeliveryAppService;
import com.sjherp.app.sales.SalesInvoiceAppService;
import com.sjherp.app.sales.SalesOrderAppService;
import com.sjherp.domain.catalog.InventoryCategory;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductCommand;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.catalog.Unit;
import com.sjherp.domain.catalog.UnitService;
import com.sjherp.domain.collection.CollectionReceipt;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.fund.PaymentAccount;
import com.sjherp.domain.fund.PaymentAccountService;
import com.sjherp.domain.fund.PaymentAccountType;
import com.sjherp.domain.gap.GapRecord;
import com.sjherp.domain.gap.GapRecordCommand;
import com.sjherp.domain.gap.GapRecordService;
import com.sjherp.domain.gap.GapStatus;
import com.sjherp.domain.gap.BusinessModule;
import com.sjherp.domain.gap.GapSeverity;
import com.sjherp.domain.gl.AccountingPeriodService;
import com.sjherp.domain.partner.Customer;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.domain.partner.Supplier;
import com.sjherp.domain.partner.SupplierService;
import com.sjherp.domain.payable.AccountsPayable;
import com.sjherp.domain.payable.AccountsPayableRepository;
import com.sjherp.domain.payable.PayableStatus;
import com.sjherp.domain.payment.PaymentDisbursement;
import com.sjherp.domain.purchase.PurchaseInvoice;
import com.sjherp.domain.purchase.PurchaseOrder;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.receivable.AccountsReceivable;
import com.sjherp.domain.receivable.ReceivableRepository;
import com.sjherp.domain.receivable.ReceivableStatus;
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesInvoice;
import com.sjherp.domain.sales.SalesOrder;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseQuery;
import com.sjherp.domain.warehouse.WarehouseService;

@ExtendWith(MockitoExtension.class)
class DemoSeedServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String OPERATOR = "qa-admin";

    @Mock private UnitService unitService;
    @Mock private ProductService productService;
    @Mock private WarehouseService warehouseService;
    @Mock private SupplierService supplierService;
    @Mock private CustomerService customerService;
    @Mock private PaymentAccountService paymentAccountService;
    @Mock private InventoryAdjustmentService inventoryAdjustmentService;
    @Mock private PurchaseOrderAppService purchaseOrderAppService;
    @Mock private PurchaseReceiptAppService purchaseReceiptAppService;
    @Mock private PurchaseInvoiceAppService purchaseInvoiceAppService;
    @Mock private PaymentDisbursementAppService paymentDisbursementAppService;
    @Mock private SalesOrderAppService salesOrderAppService;
    @Mock private SalesDeliveryAppService salesDeliveryAppService;
    @Mock private SalesInvoiceAppService salesInvoiceAppService;
    @Mock private CollectionReceiptAppService collectionReceiptAppService;
    @Mock private AccountingPeriodService accountingPeriodService;
    @Mock private PeriodCloseService periodCloseService;
    @Mock private AccountsPayableRepository accountsPayableRepository;
    @Mock private ReceivableRepository receivableRepository;
    @Mock private GapRecordService gapRecordService;
    @Mock private TransactionTemplate transactionTemplate;

    private DemoSeedService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionTemplate.execute(org.mockito.ArgumentMatchers.<TransactionCallback<DemoSeedResult>>any()))
                .thenAnswer(invocation -> {
                    TransactionCallback<?> callback = invocation.getArgument(0);
                    return callback.doInTransaction(null);
                });
        service = new DemoSeedService(unitService, productService, warehouseService, supplierService,
                customerService, paymentAccountService, inventoryAdjustmentService, purchaseOrderAppService,
                purchaseReceiptAppService, purchaseInvoiceAppService, paymentDisbursementAppService,
                salesOrderAppService, salesDeliveryAppService, salesInvoiceAppService,
                collectionReceiptAppService, accountingPeriodService, periodCloseService,
                accountsPayableRepository, receivableRepository, gapRecordService, CLOCK,
                transactionTemplate);
    }

    @Test
    void 空操作人拒绝() {
        assertThatThrownBy(() -> service.seed("sunshine", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operator");
    }

    @Test
    void 首次装载走完建审过账且不关账() {
        stubEmptyLookups();
        stubCreatesAndPosts();

        DemoSeedResult result = service.seed("sunshine", OPERATOR);

        assertThat(result.alreadyLoaded()).isFalse();
        assertThat(result.period()).isEqualTo("202610");
        assertThat(result.companyLabel()).isEqualTo("通润驱动");
        assertThat(result.purchaseOrderNo()).isEqualTo("PO-1");
        assertThat(result.collectionDocNo()).isEqualTo("RCPT-1");
        assertThat(result.sampleGapNo()).isEqualTo("GAP-1");
        assertThat(result.periodCloseable()).isTrue();

        InOrder purchase = inOrder(purchaseOrderAppService, purchaseReceiptAppService,
                purchaseInvoiceAppService, paymentDisbursementAppService);
        purchase.verify(purchaseOrderAppService).create(anyLong(), any(LocalDate.class), anyString(), any(),
                eq(OPERATOR));
        purchase.verify(purchaseOrderAppService).approve("PO-1", OPERATOR);
        purchase.verify(purchaseReceiptAppService).create(eq("PO-1"), anyLong(), any(LocalDate.class),
                anyString(), any(), eq(OPERATOR));
        purchase.verify(purchaseReceiptAppService).approve("PR-1", OPERATOR);
        purchase.verify(purchaseReceiptAppService).post("PR-1", OPERATOR);
        purchase.verify(purchaseInvoiceAppService).create(eq("PR-1"), any(), any(), any(), any(), eq(OPERATOR));
        purchase.verify(purchaseInvoiceAppService).approve("PINV-1", OPERATOR);
        purchase.verify(purchaseInvoiceAppService).post("PINV-1", OPERATOR);
        purchase.verify(paymentDisbursementAppService).create(anyLong(), anyLong(), any(), any(), any(),
                eq(OPERATOR));
        purchase.verify(paymentDisbursementAppService).approve("PAYV-1", OPERATOR);
        purchase.verify(paymentDisbursementAppService).post("PAYV-1", OPERATOR);

        InOrder sales = inOrder(salesOrderAppService, salesDeliveryAppService, salesInvoiceAppService,
                collectionReceiptAppService);
        sales.verify(salesOrderAppService).create(anyLong(), any(), any(), any(), any(), eq(OPERATOR));
        sales.verify(salesOrderAppService).approve("SO-1", OPERATOR);
        sales.verify(salesDeliveryAppService).create(eq("SO-1"), anyLong(), any(), any(), eq(OPERATOR));
        sales.verify(salesDeliveryAppService).approve("SD-1", OPERATOR);
        sales.verify(salesDeliveryAppService).post("SD-1", OPERATOR);
        sales.verify(salesInvoiceAppService).create(eq("SD-1"), any(), any(), any(), any(), eq(OPERATOR));
        sales.verify(salesInvoiceAppService).approve("SINV-1", OPERATOR);
        sales.verify(salesInvoiceAppService).post("SINV-1", OPERATOR);
        sales.verify(collectionReceiptAppService).create(anyLong(), anyLong(), any(), any(), any(),
                eq(OPERATOR));
        sales.verify(collectionReceiptAppService).approve("RCPT-1", OPERATOR);
        sales.verify(collectionReceiptAppService).post("RCPT-1", OPERATOR);

        verify(inventoryAdjustmentService).opening(eq(51L), eq(12L), eq(new BigDecimal("20")),
                eq(new BigDecimal("8.00")), eq(OPERATOR));
        verify(periodCloseService).precheck("202610");
        verify(periodCloseService, never()).close(any(), any());
        verify(gapRecordService).create(any(GapRecordCommand.class), eq(OPERATOR));
    }

    @Test
    void 已装载则幂等跳过不再建单() {
        stubEmptyLookups();
        when(warehouseService.search(any())).thenReturn(new PageResult<>(List.of(warehouse()), 1, 1, 50));
        when(supplierService.search(any())).thenReturn(new PageResult<>(List.of(supplier()), 1, 1, 50));
        when(customerService.search(any())).thenReturn(new PageResult<>(List.of(customer()), 1, 1, 50));
        when(paymentAccountService.search(any()))
                .thenReturn(new PageResult<>(List.of(paymentAccount()), 1, 1, 50));
        PurchaseOrder existing = org.mockito.Mockito.mock(PurchaseOrder.class);
        when(existing.getDocNo()).thenReturn("PO-EXIST");
        when(existing.getRemark()).thenReturn("SJHERP-DEMO-SEED:sunshine 通润驱动");
        when(purchaseOrderAppService.search(any())).thenReturn(new PageResult<>(List.of(existing), 1, 1, 20));
        when(accountingPeriodService.listAll()).thenReturn(List.of());
        when(accountingPeriodService.isOpen("202610")).thenReturn(true);
        when(periodCloseService.precheck("202610")).thenReturn(readiness());
        when(gapRecordService.search(any())).thenReturn(new PageResult<>(List.of(), 0, 1, 50));

        DemoSeedResult result = service.seed("sunshine", OPERATOR);

        assertThat(result.alreadyLoaded()).isTrue();
        assertThat(result.purchaseOrderNo()).isEqualTo("PO-EXIST");
        verify(purchaseOrderAppService, never()).create(anyLong(), any(), any(), any(), any());
        verify(purchaseReceiptAppService, never()).post(any(), any());
        verify(periodCloseService, never()).close(any(), any());
    }

    @Test
    void 单租户已装另一包则拒绝() {
        when(warehouseService.search(any())).thenAnswer(invocation -> {
            WarehouseQuery query = invocation.getArgument(0);
            String keyword = query.keyword();
            if (keyword != null && keyword.contains("演示青禾仓")) {
                return new PageResult<>(List.of(Warehouse.restore(99L, "WH-Q", "演示青禾仓", "", "",
                        false, ArchiveStatus.ENABLED, OPERATOR, NOW, OPERATOR, NOW)), 1, 1, 50);
            }
            return new PageResult<>(List.of(), 0, 1, 50);
        });

        assertThatThrownBy(() -> service.seed("sunshine", OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qinghe");
        verify(purchaseOrderAppService, never()).create(anyLong(), any(), any(), any(), any());
        verify(transactionTemplate, never()).execute(any());
    }

    private void stubEmptyLookups() {
        lenient().when(unitService.findAll()).thenReturn(List.of());
        lenient().when(warehouseService.search(any())).thenReturn(new PageResult<>(List.of(), 0, 1, 50));
        lenient().when(supplierService.search(any())).thenReturn(new PageResult<>(List.of(), 0, 1, 50));
        lenient().when(customerService.search(any())).thenReturn(new PageResult<>(List.of(), 0, 1, 50));
        lenient().when(paymentAccountService.search(any())).thenReturn(new PageResult<>(List.of(), 0, 1, 50));
        lenient().when(productService.search(any())).thenReturn(new PageResult<>(List.of(), 0, 1, 50));
        lenient().when(purchaseOrderAppService.search(any())).thenReturn(new PageResult<>(List.of(), 0, 1, 20));
        lenient().when(gapRecordService.search(any())).thenReturn(new PageResult<>(List.of(), 0, 1, 50));
        lenient().when(accountingPeriodService.listAll()).thenReturn(List.of());
        lenient().when(accountingPeriodService.isOpen("202610")).thenReturn(true);
        lenient().when(periodCloseService.precheck("202610")).thenReturn(readiness());
    }

    private void stubCreatesAndPosts() {
        when(unitService.create(eq("件"), eq(0), eq(OPERATOR))).thenReturn(unit());
        when(warehouseService.create(any(), eq(OPERATOR))).thenReturn(warehouse());
        when(supplierService.create(any(), eq(OPERATOR))).thenReturn(supplier());
        when(customerService.create(any(), eq(OPERATOR))).thenReturn(customer());
        when(paymentAccountService.create(any(), eq(OPERATOR))).thenReturn(paymentAccount());
        when(productService.create(any(), eq(OPERATOR))).thenAnswer(invocation -> {
            ProductCommand command = invocation.getArgument(0);
            long id = command.name().contains("减速电机") ? 11L : 12L;
            return product(id, command.name());
        });

        PurchaseOrder po = doc(PurchaseOrder.class, "PO-1");
        PurchaseReceipt pr = doc(PurchaseReceipt.class, "PR-1");
        PurchaseInvoice pinv = doc(PurchaseInvoice.class, "PINV-1");
        PaymentDisbursement payv = doc(PaymentDisbursement.class, "PAYV-1");
        SalesOrder so = doc(SalesOrder.class, "SO-1");
        SalesDelivery sd = doc(SalesDelivery.class, "SD-1");
        SalesInvoice sinv = doc(SalesInvoice.class, "SINV-1");
        CollectionReceipt rcpt = doc(CollectionReceipt.class, "RCPT-1");

        when(purchaseOrderAppService.create(anyLong(), any(), any(), any(), eq(OPERATOR))).thenReturn(po);
        when(purchaseOrderAppService.approve("PO-1", OPERATOR)).thenReturn(po);
        when(purchaseReceiptAppService.create(eq("PO-1"), anyLong(), any(), any(), any(), eq(OPERATOR)))
                .thenReturn(pr);
        when(purchaseReceiptAppService.approve("PR-1", OPERATOR)).thenReturn(pr);
        when(purchaseReceiptAppService.post("PR-1", OPERATOR)).thenReturn(pr);
        when(purchaseInvoiceAppService.create(eq("PR-1"), any(), any(), any(), any(), eq(OPERATOR)))
                .thenReturn(pinv);
        when(purchaseInvoiceAppService.approve("PINV-1", OPERATOR)).thenReturn(pinv);
        when(purchaseInvoiceAppService.post("PINV-1", OPERATOR)).thenReturn(pinv);
        when(accountsPayableRepository.findBySourceDocNo("PINV-1")).thenReturn(List.of(
                AccountsPayable.restore(8L, 21L, new BigDecimal("1250.00"), "PINV-1",
                        LocalDate.of(2026, 11, 15), PayableStatus.OPEN, BigDecimal.ZERO, OPERATOR, NOW)));
        when(paymentDisbursementAppService.create(anyLong(), anyLong(), any(), any(), any(), eq(OPERATOR)))
                .thenReturn(payv);
        when(paymentDisbursementAppService.approve("PAYV-1", OPERATOR)).thenReturn(payv);
        when(paymentDisbursementAppService.post("PAYV-1", OPERATOR)).thenReturn(payv);

        when(salesOrderAppService.create(anyLong(), any(), any(), any(), any(), eq(OPERATOR)))
                .thenReturn(new SalesOrderAppService.CreateResult(so, List.of()));
        when(salesOrderAppService.approve("SO-1", OPERATOR)).thenReturn(so);
        when(salesDeliveryAppService.create(eq("SO-1"), anyLong(), any(), any(), eq(OPERATOR))).thenReturn(sd);
        when(salesDeliveryAppService.approve("SD-1", OPERATOR)).thenReturn(sd);
        when(salesDeliveryAppService.post("SD-1", OPERATOR)).thenReturn(sd);
        when(salesInvoiceAppService.create(eq("SD-1"), any(), any(), any(), any(), eq(OPERATOR)))
                .thenReturn(sinv);
        when(salesInvoiceAppService.approve("SINV-1", OPERATOR)).thenReturn(sinv);
        when(salesInvoiceAppService.post("SINV-1", OPERATOR)).thenReturn(sinv);
        when(receivableRepository.findBySourceDocNo("SINV-1")).thenReturn(List.of(
                AccountsReceivable.restore(9L, 31L, new BigDecimal("1500.00"), BigDecimal.ZERO, "SINV-1",
                        LocalDate.of(2026, 11, 15), ReceivableStatus.OPEN, OPERATOR)));
        when(collectionReceiptAppService.create(anyLong(), anyLong(), any(), any(), any(), eq(OPERATOR)))
                .thenReturn(rcpt);
        when(collectionReceiptAppService.approve("RCPT-1", OPERATOR)).thenReturn(rcpt);
        when(collectionReceiptAppService.post("RCPT-1", OPERATOR)).thenReturn(rcpt);
        when(gapRecordService.create(any(GapRecordCommand.class), eq(OPERATOR))).thenReturn(
                GapRecord.restore(1L, "GAP-1", null, DemoSeedService.SAMPLE_GAP_PREFIX + "通润驱动x",
                        "场景", "期望", "缺失", BusinessModule.FINANCE, GapSeverity.LOW, GapStatus.NEW,
                        OPERATOR, OPERATOR, NOW, OPERATOR, NOW));
    }

    private static PeriodCloseReadiness readiness() {
        return new PeriodCloseReadiness("202610", "OPEN", true, false, List.of(), List.of(), List.of(),
                "1500.00", "750.00", "750.00", "0.00", "0.00");
    }

    private static <T> T doc(Class<T> type, String docNo) {
        return org.mockito.Mockito.mock(type, invocation -> {
            if ("getDocNo".equals(invocation.getMethod().getName())
                    && invocation.getMethod().getParameterCount() == 0) {
                return docNo;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private static Unit unit() {
        return Unit.restore(61L, "件", 0, OPERATOR, NOW, OPERATOR, NOW);
    }

    private static Warehouse warehouse() {
        return Warehouse.restore(51L, "WH-1", "通润驱动仓", "", "", false, ArchiveStatus.ENABLED,
                OPERATOR, NOW, OPERATOR, NOW);
    }

    private static Supplier supplier() {
        return Supplier.restore(21L, "SUP-1", "通润配套供应商", "", "", "", "", SettlementMethod.MONTHLY,
                ArchiveStatus.ENABLED, OPERATOR, NOW, OPERATOR, NOW);
    }

    private static Customer customer() {
        return Customer.restore(31L, "CUS-1", "通润主机厂客户", "", "", "", "", SettlementMethod.MONTHLY,
                null, "CNY", ArchiveStatus.ENABLED, OPERATOR, NOW, OPERATOR, NOW);
    }

    private static PaymentAccount paymentAccount() {
        return PaymentAccount.restore(41L, "FA-1", "通润驱动工行基本户", PaymentAccountType.BANK, "1002",
                "工商银行", "6222-DEMO-0001", ArchiveStatus.ENABLED, OPERATOR, NOW, OPERATOR, NOW);
    }

    private static Product product(long id, String name) {
        return Product.restore(id, "SKU-" + id, name, "", null, InventoryCategory.MERCHANDISE, 61L, "",
                ArchiveStatus.ENABLED, "", List.of(), OPERATOR, NOW, OPERATOR, NOW);
    }
}
