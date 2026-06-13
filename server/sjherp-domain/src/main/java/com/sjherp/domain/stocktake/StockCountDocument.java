package com.sjherp.domain.stocktake;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.BusinessDocument;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.audit.AuditTarget;

/**
 * 库存盘点单（M3-T03，拆解 docs/M3拆解-库存与成本.md §1.7）。
 *
 * <p>单仓盘点：单据头固定一个 {@link #warehouseId 仓库}，行项目（{@link StockCountLine}）
 * 是该仓内逐个商品的盘点结果。走 {@link BusinessDocument} 状态机
 * （DRAFT → APPROVED → EXECUTING → COMPLETED；DRAFT → CANCELLED），
 * 在过账时（EXECUTING）由领域服务调库存唯一写入口产生盘盈/盘亏流水。
 *
 * <h2>状态语义（本单据收紧规则）</h2>
 * <ul>
 *   <li>DRAFT：可录入/修改实盘数量，可作废；</li>
 *   <li>APPROVED：实盘数据锁定（审核前必须每行都已录入实盘——见 {@link #beforeTransition}）；</li>
 *   <li>EXECUTING：过账中（领域服务在此调库存服务产生流水）；</li>
 *   <li>COMPLETED：盘点完成、流水已落账，自此只可冲销（红字盘点单 M4-T07 统一做）。</li>
 * </ul>
 *
 * <p>流水不在本单据内产生——单据只管状态与数据，过账由 {@link StockCountService} 编排，
 * 经库存服务唯一写入口（CLAUDE.md 原则 1）。
 */
public final class StockCountDocument extends BusinessDocument implements AuditTarget {

    /** 盘点仓库 id（单仓盘点，单据头固定）；存在性/启用校验在 app 入口层 */
    private final long warehouseId;

    /** 盘点说明（可空，如「2026 年 6 月月末盘点」） */
    private final String remark;

    /** 行项目（按行号有序，建单后行集合不变，只允许录入实盘） */
    private final List<StockCountLine> lines;

    private StockCountDocument(String docNo, long warehouseId, String remark,
                              List<StockCountLine> lines, String createdBy) {
        super(docNo, createdBy);
        this.warehouseId = warehouseId;
        this.remark = remark;
        this.lines = new ArrayList<>(lines);
    }

    /**
     * 新建盘点单（草稿）：行集合由 app 层按建单时账面快照生成。
     *
     * @param docNo       单据号（SC-年月-序号，由 DocumentNumberGenerator 生成）
     * @param warehouseId 盘点仓库 id
     * @param remark      盘点说明（可空）
     * @param lines       行项目（至少一行，行号在单据内唯一）
     * @param createdBy   创建人
     */
    public static StockCountDocument create(String docNo, long warehouseId, String remark,
                                            List<StockCountLine> lines, String createdBy) {
        Objects.requireNonNull(lines, "盘点行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("盘点单至少要有一行");
        }
        List<Integer> lineNos = lines.stream().map(StockCountLine::getLineNo).distinct().toList();
        if (lineNos.size() != lines.size()) {
            throw new IllegalArgumentException("盘点单行号不能重复");
        }
        return new StockCountDocument(docNo, warehouseId, remark, lines, createdBy);
    }

    /**
     * 持久层重建工厂（不重跑业务校验）：用既有状态恢复单据。
     *
     * @param status 落库的单据状态
     */
    public static StockCountDocument restore(String docNo, long warehouseId, String remark,
                                             DocumentStatus status, List<StockCountLine> lines,
                                             String createdBy) {
        StockCountDocument document = new StockCountDocument(docNo, warehouseId, remark, lines, createdBy);
        document.restoreStatus(status);
        return document;
    }

    /**
     * 录入某行实盘数量（仅草稿可改）。
     *
     * @param lineNo  行号
     * @param counted 实盘数量（≥0，基本单位）
     */
    public void enterCounted(int lineNo, BigDecimal counted) {
        requireDraftForEditing();
        StockCountLine line = lineByNo(lineNo);
        line.enterCounted(counted);
    }

    /** 是否所有行都已录入实盘（审核前置条件） */
    public boolean allLinesCounted() {
        return lines.stream().allMatch(StockCountLine::isCounted);
    }

    /**
     * 流转前校验（只收紧、不放宽流转表）：审核（DRAFT→APPROVED）前必须每行都已录入实盘，
     * 否则差异无从计算、过账口径不完整。
     */
    @Override
    protected void beforeTransition(DocumentStatus from, DocumentStatus to, String operator) {
        if (from == DocumentStatus.DRAFT && to == DocumentStatus.APPROVED && !allLinesCounted()) {
            throw new IllegalArgumentException("盘点单[" + getDocNo()
                    + "] 仍有行未录入实盘数量，不能审核");
        }
    }

    /** 录入校验：非草稿不允许再改实盘（业务内容自审核起锁定） */
    private void requireDraftForEditing() {
        if (getStatus() != DocumentStatus.DRAFT) {
            throw new IllegalStateException("盘点单[" + getDocNo() + "] 当前状态 " + getStatus()
                    + " 不允许修改实盘数量（仅草稿可录入）");
        }
    }

    private StockCountLine lineByNo(int lineNo) {
        return lines.stream().filter(line -> line.getLineNo() == lineNo).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("盘点单[" + getDocNo()
                        + "] 不存在行号 " + lineNo));
    }

    public long getWarehouseId() {
        return warehouseId;
    }

    public String getRemark() {
        return remark;
    }

    /** 行项目只读视图（不可变，防外部直接增删行） */
    public List<StockCountLine> getLines() {
        return List.copyOf(lines);
    }

    // ---------------------------------------------------------------
    // AuditTarget（审计切面从 @Audited 写方法返回值提取目标标识与摘要）
    // ---------------------------------------------------------------

    @Override
    public Long auditTargetId() {
        // 盘点单无数据库自增 id 暴露（聚合以单据号为业务键），统一返回 null
        return null;
    }

    @Override
    public String auditTargetCode() {
        return getDocNo();
    }

    @Override
    public String auditSummary() {
        long countedLines = lines.stream().filter(StockCountLine::isCounted).count();
        return "仓库=" + warehouseId + ", 状态=" + getStatus() + ", 行数=" + lines.size()
                + ", 已录入=" + countedLines + ", 说明=" + AuditTarget.text(remark);
    }
}
