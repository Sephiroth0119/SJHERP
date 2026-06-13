package com.sjherp.domain.purchase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.BusinessDocument;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.audit.AuditTarget;
import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 采购发票（M3-T07，路线图 §5 采购线，为 M4 应付铺路）。
 *
 * <p>登记供应商开来的发票并与采购入库单勾稽（三单匹配从简）：单据头固定引用一张采购入库单
 * {@link #purchaseReceiptNo}、供应商 {@link #supplierId}、发票日期 {@link #invoiceDate}；行项目
 * （{@link PurchaseInvoiceLine}）逐行引用收货行开票。走 {@link BusinessDocument} 状态机
 * （DRAFT → APPROVED → EXECUTING → COMPLETED；DRAFT → CANCELLED）。
 *
 * <h2>状态语义（本单据收紧规则）</h2>
 * <ul>
 *   <li>DRAFT：草稿，可作废；</li>
 *   <li>APPROVED：审核后业务内容锁定；</li>
 *   <li>EXECUTING：过账中（领域服务在此生成应付账款）；</li>
 *   <li>COMPLETED：过账完成、应付已生成，自此只可冲销（红字发票 M4-T07）。</li>
 * </ul>
 *
 * <p>过账生成应付账款（{@code domain/payable}）：金额 = 发票总额，到期日由供应商结算方式推算，
 * 状态 OPEN（未核销，核销在 M4-T03）。发票不动库存（库存已在收货过账时入账）。
 *
 * <h2>核心约束（建单时强制）</h2>
 * 至少一行，行号唯一、引用收货行有效、数量 > 0、金额 ≥ 0（由 {@link PurchaseInvoiceLine} 守门）；
 * 三单匹配「开票数量 ≤ 已收数量」由 {@link PurchaseInvoiceService} 引用收货单时校验。
 */
public final class PurchaseInvoice extends BusinessDocument implements AuditTarget {

    /** 引用的采购入库单号（三单匹配按其各行已收数量校验开票数量） */
    private final String purchaseReceiptNo;

    /** 供应商 id（与收货单引用的采购订单供应商一致，由服务取自来源链） */
    private final long supplierId;

    /** 发票日期（业务日期，到期日推算基准） */
    private final LocalDate invoiceDate;

    /** 供应商发票号（外部票据号，可空，便于对账） */
    private final String supplierInvoiceNo;

    /** 发票说明（可空） */
    private final String remark;

    /** 行项目（按行号有序，建单后行集合不变） */
    private final List<PurchaseInvoiceLine> lines;

    private PurchaseInvoice(String docNo, String purchaseReceiptNo, long supplierId, LocalDate invoiceDate,
                           String supplierInvoiceNo, String remark, List<PurchaseInvoiceLine> lines,
                           String createdBy) {
        super(docNo, createdBy);
        this.purchaseReceiptNo = Objects.requireNonNull(purchaseReceiptNo, "引用的采购入库单号不能为空");
        this.supplierId = supplierId;
        this.invoiceDate = Objects.requireNonNull(invoiceDate, "发票日期不能为空");
        this.supplierInvoiceNo = supplierInvoiceNo;
        this.remark = remark;
        this.lines = new ArrayList<>(lines);
    }

    /**
     * 新建采购发票（草稿）。
     *
     * @param docNo             单据号（PINV-年月-序号，由 DocumentNumberGenerator 生成）
     * @param purchaseReceiptNo 引用的采购入库单号
     * @param supplierId        供应商 id（取自来源链）
     * @param invoiceDate       发票日期
     * @param supplierInvoiceNo 供应商发票号（可空）
     * @param remark            发票说明（可空）
     * @param lines             行项目（至少一行，行号在单据内唯一）
     * @param createdBy         创建人
     */
    public static PurchaseInvoice create(String docNo, String purchaseReceiptNo, long supplierId,
                                         LocalDate invoiceDate, String supplierInvoiceNo, String remark,
                                         List<PurchaseInvoiceLine> lines, String createdBy) {
        Objects.requireNonNull(lines, "采购发票行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("采购发票至少要有一行");
        }
        List<Integer> lineNos = lines.stream().map(PurchaseInvoiceLine::getLineNo).distinct().toList();
        if (lineNos.size() != lines.size()) {
            throw new IllegalArgumentException("采购发票行号不能重复");
        }
        return new PurchaseInvoice(docNo, purchaseReceiptNo, supplierId, invoiceDate, supplierInvoiceNo,
                remark, lines, createdBy);
    }

    /** 持久层重建工厂（不重跑业务校验）：用既有状态恢复单据。 */
    public static PurchaseInvoice restore(String docNo, String purchaseReceiptNo, long supplierId,
                                          LocalDate invoiceDate, String supplierInvoiceNo, String remark,
                                          DocumentStatus status, List<PurchaseInvoiceLine> lines,
                                          String createdBy) {
        PurchaseInvoice invoice = new PurchaseInvoice(docNo, purchaseReceiptNo, supplierId, invoiceDate,
                supplierInvoiceNo, remark, lines, createdBy);
        invoice.restoreStatus(status);
        return invoice;
    }

    public String getPurchaseReceiptNo() {
        return purchaseReceiptNo;
    }

    public long getSupplierId() {
        return supplierId;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public String getSupplierInvoiceNo() {
        return supplierInvoiceNo;
    }

    public String getRemark() {
        return remark;
    }

    /** 行项目只读视图（不可变，防外部直接增删行） */
    public List<PurchaseInvoiceLine> getLines() {
        return List.copyOf(lines);
    }

    /** 发票总金额（各行金额之和，2 位小数；即生成的应付金额） */
    public BigDecimal totalAmount() {
        return lines.stream().map(PurchaseInvoiceLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }

    // ---------------------------------------------------------------
    // AuditTarget
    // ---------------------------------------------------------------

    @Override
    public Long auditTargetId() {
        return null;
    }

    @Override
    public String auditTargetCode() {
        return getDocNo();
    }

    @Override
    public String auditSummary() {
        return "采购入库单=" + purchaseReceiptNo + ", 供应商=" + supplierId + ", 发票日期=" + invoiceDate
                + ", 状态=" + getStatus() + ", 行数=" + lines.size()
                + ", 总金额=" + totalAmount().toPlainString()
                + ", 供应商发票号=" + AuditTarget.text(supplierInvoiceNo);
    }
}
