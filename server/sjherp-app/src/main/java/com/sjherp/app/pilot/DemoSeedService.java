package com.sjherp.app.pilot;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sjherp.app.collection.CollectionDtos.CollectionReceiptLineRequest;
import com.sjherp.app.collection.CollectionReceiptAppService;
import com.sjherp.app.gl.GlDtos.PeriodCloseReadiness;
import com.sjherp.app.gl.PeriodCloseService;
import com.sjherp.app.inventory.InventoryAdjustmentService;
import com.sjherp.app.payment.PaymentDisbursementAppService;
import com.sjherp.app.payment.PaymentDtos.PaymentDisbursementLineRequest;
import com.sjherp.app.purchase.PurchaseDtos.PurchaseInvoiceLineRequest;
import com.sjherp.app.purchase.PurchaseDtos.PurchaseOrderLineRequest;
import com.sjherp.app.purchase.PurchaseDtos.PurchaseReceiptLineRequest;
import com.sjherp.app.purchase.PurchaseInvoiceAppService;
import com.sjherp.app.purchase.PurchaseOrderAppService;
import com.sjherp.app.purchase.PurchaseReceiptAppService;
import com.sjherp.app.sales.SalesDeliveryAppService;
import com.sjherp.app.sales.SalesDtos.SalesDeliveryLineRequest;
import com.sjherp.app.sales.SalesDtos.SalesInvoiceLineRequest;
import com.sjherp.app.sales.SalesDtos.SalesOrderLineRequest;
import com.sjherp.app.sales.SalesInvoiceAppService;
import com.sjherp.app.sales.SalesOrderAppService;
import com.sjherp.domain.catalog.InventoryCategory;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductCommand;
import com.sjherp.domain.catalog.ProductQuery;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.catalog.Unit;
import com.sjherp.domain.catalog.UnitService;
import com.sjherp.domain.collection.CollectionReceipt;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.fund.PaymentAccount;
import com.sjherp.domain.fund.PaymentAccountCommand;
import com.sjherp.domain.fund.PaymentAccountQuery;
import com.sjherp.domain.fund.PaymentAccountService;
import com.sjherp.domain.fund.PaymentAccountType;
import com.sjherp.domain.gap.BusinessModule;
import com.sjherp.domain.gap.GapRecord;
import com.sjherp.domain.gap.GapRecordCommand;
import com.sjherp.domain.gap.GapRecordQuery;
import com.sjherp.domain.gap.GapRecordService;
import com.sjherp.domain.gap.GapSeverity;
import com.sjherp.domain.gl.AccountingPeriod;
import com.sjherp.domain.gl.AccountingPeriodService;
import com.sjherp.domain.inventory.CostingStrategy;
import com.sjherp.domain.partner.Customer;
import com.sjherp.domain.partner.CustomerCommand;
import com.sjherp.domain.partner.CustomerQuery;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.domain.partner.Supplier;
import com.sjherp.domain.partner.SupplierCommand;
import com.sjherp.domain.partner.SupplierQuery;
import com.sjherp.domain.partner.SupplierService;
import com.sjherp.domain.payable.AccountsPayable;
import com.sjherp.domain.payable.AccountsPayableRepository;
import com.sjherp.domain.payment.PaymentDisbursement;
import com.sjherp.domain.purchase.PurchaseInvoice;
import com.sjherp.domain.purchase.PurchaseOrder;
import com.sjherp.domain.purchase.PurchaseOrderQuery;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.receivable.AccountsReceivable;
import com.sjherp.domain.receivable.ReceivableRepository;
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesInvoice;
import com.sjherp.domain.sales.SalesOrder;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseCommand;
import com.sjherp.domain.warehouse.WarehouseQuery;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 种子编排（M8-T07）：经已有档案服务与单据 AppService 走完
 * 采购→入库→发票→付款→销售→出库→发票→收款，并登记一条样例缺口。
 * 默认展示名为通润驱动（pack id 仍为 sunshine）。
 *
 * <p>不关账、不绕过状态机、不写裸 SQL。账期取当前 UTC 月，与销售出库 COGS 凭证日对齐。
 */
@Service
public class DemoSeedService {

    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");
    private static final int LINE_NO = 1;
    static final String SAMPLE_GAP_PREFIX = "演示试点样例缺口：";

    private final UnitService unitService;
    private final ProductService productService;
    private final WarehouseService warehouseService;
    private final SupplierService supplierService;
    private final CustomerService customerService;
    private final PaymentAccountService paymentAccountService;
    private final InventoryAdjustmentService inventoryAdjustmentService;
    private final PurchaseOrderAppService purchaseOrderAppService;
    private final PurchaseReceiptAppService purchaseReceiptAppService;
    private final PurchaseInvoiceAppService purchaseInvoiceAppService;
    private final PaymentDisbursementAppService paymentDisbursementAppService;
    private final SalesOrderAppService salesOrderAppService;
    private final SalesDeliveryAppService salesDeliveryAppService;
    private final SalesInvoiceAppService salesInvoiceAppService;
    private final CollectionReceiptAppService collectionReceiptAppService;
    private final AccountingPeriodService accountingPeriodService;
    private final PeriodCloseService periodCloseService;
    private final AccountsPayableRepository accountsPayableRepository;
    private final ReceivableRepository receivableRepository;
    private final GapRecordService gapRecordService;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public DemoSeedService(
            UnitService unitService,
            ProductService productService,
            WarehouseService warehouseService,
            SupplierService supplierService,
            CustomerService customerService,
            PaymentAccountService paymentAccountService,
            InventoryAdjustmentService inventoryAdjustmentService,
            PurchaseOrderAppService purchaseOrderAppService,
            PurchaseReceiptAppService purchaseReceiptAppService,
            PurchaseInvoiceAppService purchaseInvoiceAppService,
            PaymentDisbursementAppService paymentDisbursementAppService,
            SalesOrderAppService salesOrderAppService,
            SalesDeliveryAppService salesDeliveryAppService,
            SalesInvoiceAppService salesInvoiceAppService,
            CollectionReceiptAppService collectionReceiptAppService,
            AccountingPeriodService accountingPeriodService,
            PeriodCloseService periodCloseService,
            AccountsPayableRepository accountsPayableRepository,
            ReceivableRepository receivableRepository,
            GapRecordService gapRecordService,
            PlatformTransactionManager transactionManager) {
        this(unitService, productService, warehouseService, supplierService, customerService,
                paymentAccountService, inventoryAdjustmentService, purchaseOrderAppService,
                purchaseReceiptAppService, purchaseInvoiceAppService, paymentDisbursementAppService,
                salesOrderAppService, salesDeliveryAppService, salesInvoiceAppService,
                collectionReceiptAppService, accountingPeriodService, periodCloseService,
                accountsPayableRepository, receivableRepository, gapRecordService, Clock.systemUTC(),
                new TransactionTemplate(transactionManager));
    }

    DemoSeedService(
            UnitService unitService,
            ProductService productService,
            WarehouseService warehouseService,
            SupplierService supplierService,
            CustomerService customerService,
            PaymentAccountService paymentAccountService,
            InventoryAdjustmentService inventoryAdjustmentService,
            PurchaseOrderAppService purchaseOrderAppService,
            PurchaseReceiptAppService purchaseReceiptAppService,
            PurchaseInvoiceAppService purchaseInvoiceAppService,
            PaymentDisbursementAppService paymentDisbursementAppService,
            SalesOrderAppService salesOrderAppService,
            SalesDeliveryAppService salesDeliveryAppService,
            SalesInvoiceAppService salesInvoiceAppService,
            CollectionReceiptAppService collectionReceiptAppService,
            AccountingPeriodService accountingPeriodService,
            PeriodCloseService periodCloseService,
            AccountsPayableRepository accountsPayableRepository,
            ReceivableRepository receivableRepository,
            GapRecordService gapRecordService,
            Clock clock,
            TransactionTemplate transactionTemplate) {
        this.unitService = Objects.requireNonNull(unitService);
        this.productService = Objects.requireNonNull(productService);
        this.warehouseService = Objects.requireNonNull(warehouseService);
        this.supplierService = Objects.requireNonNull(supplierService);
        this.customerService = Objects.requireNonNull(customerService);
        this.paymentAccountService = Objects.requireNonNull(paymentAccountService);
        this.inventoryAdjustmentService = Objects.requireNonNull(inventoryAdjustmentService);
        this.purchaseOrderAppService = Objects.requireNonNull(purchaseOrderAppService);
        this.purchaseReceiptAppService = Objects.requireNonNull(purchaseReceiptAppService);
        this.purchaseInvoiceAppService = Objects.requireNonNull(purchaseInvoiceAppService);
        this.paymentDisbursementAppService = Objects.requireNonNull(paymentDisbursementAppService);
        this.salesOrderAppService = Objects.requireNonNull(salesOrderAppService);
        this.salesDeliveryAppService = Objects.requireNonNull(salesDeliveryAppService);
        this.salesInvoiceAppService = Objects.requireNonNull(salesInvoiceAppService);
        this.collectionReceiptAppService = Objects.requireNonNull(collectionReceiptAppService);
        this.accountingPeriodService = Objects.requireNonNull(accountingPeriodService);
        this.periodCloseService = Objects.requireNonNull(periodCloseService);
        this.accountsPayableRepository = Objects.requireNonNull(accountsPayableRepository);
        this.receivableRepository = Objects.requireNonNull(receivableRepository);
        this.gapRecordService = Objects.requireNonNull(gapRecordService);
        this.clock = Objects.requireNonNull(clock);
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate);
    }

    /**
     * 装载试点种子。写路径包在单一事务内（中途失败整单回滚）；关账预检在提交后执行，
     * 以便看到 afterCommit 才落库的审计行（否则 AUDIT_INTEGRITY 会误报 ERROR）。
     */
    public DemoSeedResult seed(String packId, String operator) {
        requireOperator(operator);
        DemoCompanyPack pack = DemoCompanyPack.byId(packId);
        rejectIfOtherPackPresent(pack);

        YearMonth yearMonth = YearMonth.now(clock);
        String period = yearMonth.format(PERIOD_FORMAT);
        LocalDate bizDate = yearMonth.atDay(Math.min(15, yearMonth.lengthOfMonth()));

        DemoSeedResult loaded = transactionTemplate.execute(status ->
                seedInTransaction(pack, operator, period, bizDate));
        if (loaded == null) {
            throw new IllegalStateException("种子事务未返回结果");
        }
        PeriodCloseReadiness readiness = periodCloseService.precheck(period);
        return loaded.withPrecheck(accountingPeriodService.isOpen(period), readiness);
    }

    private DemoSeedResult seedInTransaction(DemoCompanyPack pack, String operator,
                                             String period, LocalDate bizDate) {
        ensureOpenPeriod(period, operator);

        Unit unit = ensureUnit(pack.unitName(), operator);
        Warehouse warehouse = ensureWarehouse(pack, operator);
        Supplier supplier = ensureSupplier(pack, operator);
        Customer customer = ensureCustomer(pack, operator);
        PaymentAccount paymentAccount = ensurePaymentAccount(pack, operator);

        Optional<PurchaseOrder> existing = findSeededOrder(supplier.getId(), pack);
        if (existing.isPresent()) {
            return result(pack, true, period, warehouse, pack.tradeProductName(),
                    pack.openingProductName(), supplier, customer, paymentAccount,
                    existing.get().getDocNo(), null, null, null, null, null, null, null,
                    findSampleGapNo(pack), "种子已存在，跳过重复装载。可继续关账预检与缺口记录。");
        }

        ProductCreated trade = ensureProduct(pack.tradeProductName(), unit.getId(), operator);
        ProductCreated opening = ensureProduct(pack.openingProductName(), unit.getId(), operator);
        if (opening.created()) {
            inventoryAdjustmentService.opening(warehouse.getId(), opening.product().getId(),
                    pack.openingQty(), pack.openingCost(), operator);
        }

        String remark = pack.seedMarker() + " " + pack.companyLabel() + " · 试点种子";
        BigDecimal purchaseAmount = amount(pack.purchaseQty(), pack.purchasePrice());
        BigDecimal salesAmount = amount(pack.salesQty(), pack.salesPrice());

        PurchaseOrder order = purchaseOrderAppService.create(supplier.getId(), bizDate, remark,
                List.of(new PurchaseOrderLineRequest(trade.product().getId(), pack.purchaseQty(),
                        pack.purchasePrice())), operator);
        purchaseOrderAppService.approve(order.getDocNo(), operator);

        PurchaseReceipt receipt = purchaseReceiptAppService.create(order.getDocNo(), warehouse.getId(),
                bizDate, remark, List.of(new PurchaseReceiptLineRequest(LINE_NO, pack.purchaseQty(),
                        pack.purchasePrice())), operator);
        purchaseReceiptAppService.approve(receipt.getDocNo(), operator);
        purchaseReceiptAppService.post(receipt.getDocNo(), operator);

        PurchaseInvoice purchaseInvoice = purchaseInvoiceAppService.create(receipt.getDocNo(), bizDate,
                "DEMO-INV-" + pack.id(), remark,
                List.of(new PurchaseInvoiceLineRequest(LINE_NO, pack.purchaseQty(), purchaseAmount)),
                operator);
        purchaseInvoiceAppService.approve(purchaseInvoice.getDocNo(), operator);
        purchaseInvoiceAppService.post(purchaseInvoice.getDocNo(), operator);

        AccountsPayable payable = requirePayable(purchaseInvoice.getDocNo());
        PaymentDisbursement payment = paymentDisbursementAppService.create(supplier.getId(),
                paymentAccount.getId(), bizDate, remark,
                List.of(new PaymentDisbursementLineRequest(payable.getId(), payable.getAmount())),
                operator);
        paymentDisbursementAppService.approve(payment.getDocNo(), operator);
        paymentDisbursementAppService.post(payment.getDocNo(), operator);

        SalesOrder salesOrder = salesOrderAppService.create(customer.getId(), bizDate, remark,
                warehouse.getId(),
                List.of(new SalesOrderLineRequest(trade.product().getId(), pack.salesQty(),
                        pack.salesPrice())), operator).order();
        salesOrderAppService.approve(salesOrder.getDocNo(), operator);

        SalesDelivery delivery = salesDeliveryAppService.create(salesOrder.getDocNo(), warehouse.getId(),
                remark, List.of(new SalesDeliveryLineRequest(LINE_NO, trade.product().getId(),
                        pack.salesQty())), operator);
        salesDeliveryAppService.approve(delivery.getDocNo(), operator);
        salesDeliveryAppService.post(delivery.getDocNo(), operator);

        SalesInvoice salesInvoice = salesInvoiceAppService.create(delivery.getDocNo(), bizDate,
                bizDate.plusMonths(1), remark,
                List.of(new SalesInvoiceLineRequest(LINE_NO, trade.product().getId(), pack.salesQty(),
                        pack.salesPrice())), operator);
        salesInvoiceAppService.approve(salesInvoice.getDocNo(), operator);
        salesInvoiceAppService.post(salesInvoice.getDocNo(), operator);

        AccountsReceivable receivable = requireReceivable(salesInvoice.getDocNo());
        CollectionReceipt collection = collectionReceiptAppService.create(customer.getId(),
                paymentAccount.getId(), bizDate, remark,
                List.of(new CollectionReceiptLineRequest(receivable.getId(), receivable.getAmount())),
                operator);
        collectionReceiptAppService.approve(collection.getDocNo(), operator);
        collectionReceiptAppService.post(collection.getDocNo(), operator);

        String sampleGapNo = ensureSampleGap(pack, operator);
        return result(pack, false, period, warehouse, trade.product().getName(),
                opening.product().getName(), supplier, customer, paymentAccount,
                order.getDocNo(), receipt.getDocNo(), purchaseInvoice.getDocNo(), payment.getDocNo(),
                salesOrder.getDocNo(), delivery.getDocNo(), salesInvoice.getDocNo(),
                collection.getDocNo(), sampleGapNo,
                "已装入 " + pack.companyLabel() + "。采购额 " + purchaseAmount.toPlainString()
                        + "，销售额 " + salesAmount.toPlainString()
                        + "。账期仍 OPEN，请按清单做关账预检/关账，并用缺口通道记录问题。");
    }

    public List<DemoSeedDtos.PackInfo> listPacks() {
        return DemoCompanyPack.all().stream().map(DemoSeedDtos.PackInfo::from).toList();
    }

    private void rejectIfOtherPackPresent(DemoCompanyPack pack) {
        for (DemoCompanyPack other : DemoCompanyPack.all()) {
            if (other.id().equals(pack.id())) {
                continue;
            }
            if (findWarehouse(other.warehouseName()).isPresent()) {
                throw new IllegalStateException(
                        "当前库已装入种子包 " + other.id() + "（" + other.companyLabel()
                                + "）。v1.0 单租户不能并行隔离另一家企业，请恢复到空库后再装 "
                                + pack.id() + "，或继续使用已装入的种子包。");
            }
        }
    }

    private void ensureOpenPeriod(String period, String operator) {
        boolean exists = accountingPeriodService.listAll().stream()
                .anyMatch(item -> period.equals(item.getPeriod()));
        if (!exists) {
            accountingPeriodService.open(period, operator);
            return;
        }
        if (!accountingPeriodService.isOpen(period)) {
            throw new IllegalStateException("账期 " + period
                    + " 已关账，种子只写入 OPEN 账期。请换新库或重开后再装。");
        }
    }

    private Unit ensureUnit(String name, String operator) {
        return unitService.findAll().stream()
                .filter(unit -> name.equals(unit.getName()))
                .findFirst()
                .orElseGet(() -> unitService.create(name, 0, operator));
    }

    private Warehouse ensureWarehouse(DemoCompanyPack pack, String operator) {
        return findWarehouse(pack.warehouseName()).orElseGet(() -> warehouseService.create(
                new WarehouseCommand(null, pack.warehouseName(), pack.companyLabel() + "地址",
                        "仓管", false), operator));
    }

    private Optional<Warehouse> findWarehouse(String name) {
        return warehouseService.search(new WarehouseQuery(name, ArchiveStatus.ENABLED, 1, 50))
                .items().stream()
                .filter(item -> name.equals(item.getName()))
                .findFirst();
    }

    private Supplier ensureSupplier(DemoCompanyPack pack, String operator) {
        return findExact(supplierService.search(
                new SupplierQuery(pack.supplierName(), ArchiveStatus.ENABLED, 1, 50)),
                pack.supplierName(), Supplier::getName)
                .orElseGet(() -> supplierService.create(new SupplierCommand(null, pack.supplierName(),
                        "联系人", "13800000000", pack.companyLabel(), "DEMOTAX0001",
                        SettlementMethod.MONTHLY), operator));
    }

    private Customer ensureCustomer(DemoCompanyPack pack, String operator) {
        return findExact(customerService.search(
                new CustomerQuery(pack.customerName(), ArchiveStatus.ENABLED, 1, 50)),
                pack.customerName(), Customer::getName)
                .orElseGet(() -> customerService.create(new CustomerCommand(null, pack.customerName(),
                        "联系人", "13900000000", pack.companyLabel(), "DEMOCUS0001",
                        SettlementMethod.MONTHLY, null), operator));
    }

    private PaymentAccount ensurePaymentAccount(DemoCompanyPack pack, String operator) {
        return findExact(paymentAccountService.search(
                new PaymentAccountQuery(pack.paymentAccountName(), ArchiveStatus.ENABLED, 1, 50)),
                pack.paymentAccountName(), PaymentAccount::getName)
                .orElseGet(() -> paymentAccountService.create(new PaymentAccountCommand(null,
                        pack.paymentAccountName(), PaymentAccountType.BANK, pack.glAccountCode(),
                        pack.bankName(), pack.accountNo()), operator));
    }

    private ProductCreated ensureProduct(String name, long unitId, String operator) {
        Optional<Product> existing = findExact(productService.search(
                new ProductQuery(name, ArchiveStatus.ENABLED, 1, 50)), name, Product::getName);
        if (existing.isPresent()) {
            return new ProductCreated(existing.get(), false);
        }
        Product created = productService.create(new ProductCommand(null, name, "标准规格", null, unitId,
                null, "试点种子商品", List.of(), InventoryCategory.MERCHANDISE), operator);
        return new ProductCreated(created, true);
    }

    private Optional<PurchaseOrder> findSeededOrder(long supplierId, DemoCompanyPack pack) {
        PageResult<PurchaseOrder> page = purchaseOrderAppService.search(
                new PurchaseOrderQuery(supplierId, null, 1, 50));
        return page.items().stream()
                .filter(order -> order.getRemark() != null && order.getRemark().contains(pack.seedMarker()))
                .findFirst();
    }

    private AccountsPayable requirePayable(String invoiceNo) {
        List<AccountsPayable> rows = accountsPayableRepository.findBySourceDocNo(invoiceNo);
        if (rows.isEmpty()) {
            throw new IllegalStateException("采购发票[" + invoiceNo + "] 过账后未找到应付");
        }
        return rows.get(0);
    }

    private AccountsReceivable requireReceivable(String invoiceNo) {
        List<AccountsReceivable> rows = receivableRepository.findBySourceDocNo(invoiceNo);
        if (rows.isEmpty()) {
            throw new IllegalStateException("销售发票[" + invoiceNo + "] 过账后未找到应收");
        }
        return rows.get(0);
    }

    private String ensureSampleGap(DemoCompanyPack pack, String operator) {
        String existing = findSampleGapNo(pack);
        if (existing != null) {
            return existing;
        }
        GapRecord record = gapRecordService.create(new GapRecordCommand(
                null,
                SAMPLE_GAP_PREFIX + pack.companyLabel() + "希望科目余额一键导出 CSV",
                "QA 在 " + pack.companyLabel() + " 试点中核对科目余额，希望从试算/科目余额直接导出 CSV。",
                "财务工作台提供科目余额 CSV 导出，金额仍为字符串。",
                "科目余额导出尚未作为独立能力提供。",
                BusinessModule.FINANCE,
                GapSeverity.LOW,
                operator), operator);
        return record.getGapNo();
    }

    private String findSampleGapNo(DemoCompanyPack pack) {
        String needle = SAMPLE_GAP_PREFIX + pack.companyLabel();
        return gapRecordService.search(new GapRecordQuery(null, BusinessModule.FINANCE, 1, 50))
                .items().stream()
                .filter(gap -> gap.getTitle() != null && gap.getTitle().startsWith(needle))
                .map(GapRecord::getGapNo)
                .findFirst()
                .orElse(null);
    }

    private DemoSeedResult result(
            DemoCompanyPack pack, boolean alreadyLoaded, String period,
            Warehouse warehouse, String tradeProductName, String openingProductName,
            Supplier supplier, Customer customer, PaymentAccount paymentAccount,
            String purchaseOrderNo, String purchaseReceiptNo, String purchaseInvoiceNo, String paymentDocNo,
            String salesOrderNo, String salesDeliveryNo, String salesInvoiceNo, String collectionDocNo,
            String sampleGapNo, String message) {
        return new DemoSeedResult(pack.id(), pack.companyLabel(), alreadyLoaded, period, true, false,
                List.of(), warehouse.getName(), tradeProductName, openingProductName, supplier.getName(),
                customer.getName(), paymentAccount.getName(), purchaseOrderNo, purchaseReceiptNo,
                purchaseInvoiceNo, paymentDocNo, salesOrderNo, salesDeliveryNo, salesInvoiceNo,
                collectionDocNo, sampleGapNo, message);
    }

    private static <T> Optional<T> findExact(PageResult<T> page, String name,
                                             java.util.function.Function<T, String> namer) {
        return page.items().stream().filter(item -> name.equals(namer.apply(item))).findFirst();
    }

    private static BigDecimal amount(BigDecimal quantity, BigDecimal price) {
        return quantity.multiply(price).setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }

    private record ProductCreated(Product product, boolean created) {
    }
}
