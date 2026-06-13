package com.sjherp.domain.sales;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.BusinessDocument;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.inventory.CostingStrategy;
import com.sjherp.domain.common.audit.AuditTarget;

/**
 * 销售发票（M3-T10，路线图 §5 销售线）。
 *
 * <p>引用某销售出库单（{@link #salesDeliveryNo}）对已发货商品开票。单据头固定客户
 * （{@link #customerId}，冗余自出库单关联订单的客户，便于应收挂账与查询）。行项目
 * （{@link SalesInvoiceLine}）逐行对应出库行的开票数量与单价。走 {@link BusinessDocument}
 * 状态机（DRAFT → APPROVED → EXECUTING → COMPLETED；DRAFT → CANCELLED）。
 *
 * <h2>过账（审核后）</h2>
 * 单据 EXECUTING → COMPLETED，按发票金额生成一条 {@link com.sjherp.domain.receivable.AccountsReceivable
 * 应收账款}（OPEN，核销 M4-T03）。开票数量金额校验不超出库已发量在发票服务建单时完成。
 *
 * <p>财务记录只可冲销不可物理修改/删除（CLAUDE.md 原则 2，冲销 M4 统一做）。
 */
public final class SalesInvoice extends BusinessDocument implements AuditTarget {

    /** 引用的销售出库单号（开票针对它的已发货数量） */
    private final String salesDeliveryNo;

    /** 客户 id（冗余便于应收挂账与查询；与出库单关联订单客户一致） */
    private final long customerId;

    /** 开票日期 */
    private final LocalDate invoiceDate;

    /** 到期日（账期，可空，挂应收时透传） */
    private final LocalDate dueDate;

    /** 发票说明（可空） */
    private final String remark;

    /** 行项目（按行号有序，建单后行集合不变） */
    private final List<SalesInvoiceLine> lines;

    private SalesInvoice(String docNo, String salesDeliveryNo, long customerId, LocalDate invoiceDate,
                         LocalDate dueDate, String remark, List<SalesInvoiceLine> lines, String createdBy) {
        super(docNo, createdBy);
        this.salesDeliveryNo = Objects.requireNonNull(salesDeliveryNo, "关联出库单号不能为空");
        this.customerId = customerId;
        this.invoiceDate = Objects.requireNonNull(invoiceDate, "开票日期不能为空");
        this.dueDate = dueDate;
        this.remark = remark;
        this.lines = new ArrayList<>(lines);
    }

    /**
     * 新建销售发票（草稿）。
     *
     * @param docNo           单据号（SINV-年月-序号，由 DocumentNumberGenerator 生成）
     * @param salesDeliveryNo 引用的销售出库单号
     * @param customerId      客户 id
     * @param invoiceDate     开票日期
     * @param dueDate         到期日（可空）
     * @param remark          发票说明（可空）
     * @param lines           行项目（至少一行，行号在单据内唯一）
     * @param createdBy       创建人
     */
    public static SalesInvoice create(String docNo, String salesDeliveryNo, long customerId,
                                      LocalDate invoiceDate, LocalDate dueDate, String remark,
                                      List<SalesInvoiceLine> lines, String createdBy) {
        Objects.requireNonNull(lines, "发票行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("销售发票至少要有一行");
        }
        List<Integer> lineNos = lines.stream().map(SalesInvoiceLine::getLineNo).distinct().toList();
        if (lineNos.size() != lines.size()) {
            throw new IllegalArgumentException("销售发票行号不能重复");
        }
        return new SalesInvoice(docNo, salesDeliveryNo, customerId, invoiceDate, dueDate, remark,
                lines, createdBy);
    }

    /**
     * 持久层重建工厂（不重跑业务校验）：用既有状态恢复单据。
     *
     * @param status 落库的单据状态
     */
    public static SalesInvoice restore(String docNo, String salesDeliveryNo, long customerId,
                                       LocalDate invoiceDate, LocalDate dueDate, String remark,
                                       DocumentStatus status, List<SalesInvoiceLine> lines,
                                       String createdBy) {
        SalesInvoice invoice = new SalesInvoice(docNo, salesDeliveryNo, customerId, invoiceDate,
                dueDate, remark, lines, createdBy);
        invoice.restoreStatus(status);
        return invoice;
    }

    /** 发票总金额 = 各行金额之和（2 位 HALF_UP）——即应收金额 */
    public BigDecimal totalAmount() {
        return lines.stream().map(SalesInvoiceLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }

    public String getSalesDeliveryNo() {
        return salesDeliveryNo;
    }

    public long getCustomerId() {
        return customerId;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public String getRemark() {
        return remark;
    }

    /** 行项目只读视图（不可变） */
    public List<SalesInvoiceLine> getLines() {
        return List.copyOf(lines);
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
        return "关联出库单=" + salesDeliveryNo + ", 客户=" + customerId + ", 开票日期=" + invoiceDate
                + ", 状态=" + getStatus() + ", 行数=" + lines.size()
                + ", 金额=" + totalAmount().toPlainString() + ", 说明=" + AuditTarget.text(remark);
    }
}
