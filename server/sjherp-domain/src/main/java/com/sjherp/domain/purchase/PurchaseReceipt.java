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
 * 采购入库单（M3-T06，路线图 §5 采购线）。
 *
 * <p>按采购订单收货：单据头固定引用一张采购订单 {@link #purchaseOrderNo}、收货仓库
 * {@link #warehouseId} 与收货日期 {@link #receiptDate}；行项目（{@link PurchaseReceiptLine}）
 * 逐行引用采购订单行收货（支持部分收货）。走 {@link BusinessDocument} 状态机
 * （DRAFT → APPROVED → EXECUTING → COMPLETED；DRAFT → CANCELLED）。
 *
 * <h2>状态语义（本单据收紧规则）</h2>
 * <ul>
 *   <li>DRAFT：草稿，可作废；</li>
 *   <li>APPROVED：审核后业务内容锁定；</li>
 *   <li>EXECUTING：过账中（领域服务在此经库存唯一写入口产生 PURCHASE_IN 流水、回写采购订单到货量）；</li>
 *   <li>COMPLETED：收货完成、库存已入账，自此只可冲销（退货红字单 M4-T07 统一做）。</li>
 * </ul>
 *
 * <p><b>收货才动库存</b>：库存只在本单据过账（EXECUTING）时经库存唯一写入口产生
 * （CLAUDE.md 原则 1）；流水产生与采购订单到货量回写由 {@link PurchaseReceiptService} 编排，
 * 同一外层事务原子提交（拆解 §1.4）。退货（负向收货）走冲销语义，留 M4-T07。
 *
 * <h2>核心约束（建单时强制）</h2>
 * 至少一行，行号唯一、引用采购订单行号有效、数量 > 0、单价 ≥ 0（由 {@link PurchaseReceiptLine} 守门）。
 */
public final class PurchaseReceipt extends BusinessDocument implements AuditTarget {

    /** 引用的采购订单号（收货量回写到该订单各行；存在性/状态校验在服务层） */
    private final String purchaseOrderNo;

    /** 收货仓库 id（单据头固定）；存在性/启用校验在 app 入口层 */
    private final long warehouseId;

    /** 收货日期（业务日期） */
    private final LocalDate receiptDate;

    /** 收货说明（可空） */
    private final String remark;

    /** 行项目（按行号有序，建单后行集合不变） */
    private final List<PurchaseReceiptLine> lines;

    private PurchaseReceipt(String docNo, String purchaseOrderNo, long warehouseId, LocalDate receiptDate,
                           String remark, List<PurchaseReceiptLine> lines, String createdBy) {
        super(docNo, createdBy);
        this.purchaseOrderNo = Objects.requireNonNull(purchaseOrderNo, "引用的采购订单号不能为空");
        this.warehouseId = warehouseId;
        this.receiptDate = Objects.requireNonNull(receiptDate, "收货日期不能为空");
        this.remark = remark;
        this.lines = new ArrayList<>(lines);
    }

    /**
     * 新建采购入库单（草稿）。
     *
     * @param docNo           单据号（PR-年月-序号，由 DocumentNumberGenerator 生成）
     * @param purchaseOrderNo 引用的采购订单号
     * @param warehouseId     收货仓库 id
     * @param receiptDate     收货日期
     * @param remark          收货说明（可空）
     * @param lines           行项目（至少一行，行号在单据内唯一）
     * @param createdBy       创建人
     */
    public static PurchaseReceipt create(String docNo, String purchaseOrderNo, long warehouseId,
                                         LocalDate receiptDate, String remark,
                                         List<PurchaseReceiptLine> lines, String createdBy) {
        Objects.requireNonNull(lines, "采购入库单行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("采购入库单至少要有一行");
        }
        List<Integer> lineNos = lines.stream().map(PurchaseReceiptLine::getLineNo).distinct().toList();
        if (lineNos.size() != lines.size()) {
            throw new IllegalArgumentException("采购入库单行号不能重复");
        }
        return new PurchaseReceipt(docNo, purchaseOrderNo, warehouseId, receiptDate, remark,
                lines, createdBy);
    }

    /** 持久层重建工厂（不重跑业务校验）：用既有状态恢复单据。 */
    public static PurchaseReceipt restore(String docNo, String purchaseOrderNo, long warehouseId,
                                          LocalDate receiptDate, String remark, DocumentStatus status,
                                          List<PurchaseReceiptLine> lines, String createdBy) {
        PurchaseReceipt receipt = new PurchaseReceipt(docNo, purchaseOrderNo, warehouseId, receiptDate,
                remark, lines, createdBy);
        receipt.restoreStatus(status);
        return receipt;
    }

    public String getPurchaseOrderNo() {
        return purchaseOrderNo;
    }

    public long getWarehouseId() {
        return warehouseId;
    }

    public LocalDate getReceiptDate() {
        return receiptDate;
    }

    public String getRemark() {
        return remark;
    }

    /**
     * 回写本次开票数量到指定收货行（采购发票 M3-T07 过账时由 {@link PurchaseReceiptService} 编排调用）。
     * 累加后已开票量不得超过收货量（跨发票累计校验，超量拒绝——防跨发票超额开票虚增应付，
     * CLAUDE.md 原则 2）。仅已过账（COMPLETED，库存已入账且已可开票）的收货单可回写。
     *
     * @param lineNo  收货行号
     * @param invoiced 本次开票数量（> 0，基本单位）
     */
    public void invoiceLine(int lineNo, BigDecimal invoiced) {
        if (getStatus() != DocumentStatus.COMPLETED) {
            throw new IllegalStateException("采购入库单[" + getDocNo() + "] 当前状态 " + getStatus()
                    + " 未过账，不可回写开票量");
        }
        lineByNo(lineNo).addInvoiced(invoiced);
    }

    private PurchaseReceiptLine lineByNo(int lineNo) {
        return lines.stream().filter(l -> l.getLineNo() == lineNo).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "采购入库单[" + getDocNo() + "] 不存在行号 " + lineNo));
    }

    /** 行项目只读视图（不可变，防外部直接增删行） */
    public List<PurchaseReceiptLine> getLines() {
        return List.copyOf(lines);
    }

    /** 入库总金额（各行金额之和，2 位小数） */
    public BigDecimal totalAmount() {
        return lines.stream().map(PurchaseReceiptLine::getAmount)
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
        return "采购订单=" + purchaseOrderNo + ", 收货仓=" + warehouseId + ", 收货日期=" + receiptDate
                + ", 状态=" + getStatus() + ", 行数=" + lines.size()
                + ", 总金额=" + totalAmount().toPlainString() + ", 说明=" + AuditTarget.text(remark);
    }
}
