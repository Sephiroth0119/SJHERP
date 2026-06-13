package com.sjherp.domain.transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.BusinessDocument;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.audit.AuditTarget;

/**
 * 库存调拨单（M3-T04，拆解 docs/M3拆解-库存与成本.md §1.6.5 调拨成本守恒）。
 *
 * <p>仓间调拨：单据头固定一个{@link #fromWarehouseId 调出仓}与一个{@link #toWarehouseId 调入仓}，
 * 行项目（{@link TransferLine}）是逐商品的调拨数量。走 {@link BusinessDocument} 状态机
 * （DRAFT → APPROVED → EXECUTING → COMPLETED；DRAFT → CANCELLED），
 * 在过账时（EXECUTING）由领域服务调库存唯一写入口产生两腿（调出 + 调入）流水。
 *
 * <h2>状态语义（本单据收紧规则）</h2>
 * <ul>
 *   <li>DRAFT：草稿，可作废；</li>
 *   <li>APPROVED：审核后业务内容锁定；</li>
 *   <li>EXECUTING：过账中（领域服务在此调库存服务产生两腿流水）；</li>
 *   <li>COMPLETED：调拨完成、两腿流水已落账，自此只可冲销（红字调拨单 M4 统一做）。</li>
 * </ul>
 *
 * <h2>核心约束（建单时强制）</h2>
 * 调出仓 ≠ 调入仓（同仓调拨无意义且会在同一余额行上一出一入虚增流水），至少一行，
 * 行号唯一、数量 > 0（由 {@link TransferLine} 守门）。
 *
 * <p>流水不在本单据内产生——单据只管状态与数据，过账由 {@link TransferService} 编排，
 * 经库存服务唯一写入口（CLAUDE.md 原则 1）。
 */
public final class TransferDocument extends BusinessDocument implements AuditTarget {

    /** 调出仓库 id（单据头固定）；存在性/启用校验在 app 入口层 */
    private final long fromWarehouseId;

    /** 调入仓库 id（单据头固定）；存在性/启用校验在 app 入口层 */
    private final long toWarehouseId;

    /** 调拨说明（可空，如「门店补货」） */
    private final String remark;

    /** 行项目（按行号有序，建单后行集合不变） */
    private final List<TransferLine> lines;

    private TransferDocument(String docNo, long fromWarehouseId, long toWarehouseId, String remark,
                            List<TransferLine> lines, String createdBy) {
        super(docNo, createdBy);
        this.fromWarehouseId = fromWarehouseId;
        this.toWarehouseId = toWarehouseId;
        this.remark = remark;
        this.lines = new ArrayList<>(lines);
    }

    /**
     * 新建调拨单（草稿）。
     *
     * @param docNo           单据号（TR-年月-序号，由 DocumentNumberGenerator 生成）
     * @param fromWarehouseId 调出仓库 id
     * @param toWarehouseId   调入仓库 id（必须 ≠ 调出仓）
     * @param remark          调拨说明（可空）
     * @param lines           行项目（至少一行，行号在单据内唯一）
     * @param createdBy       创建人
     */
    public static TransferDocument create(String docNo, long fromWarehouseId, long toWarehouseId,
                                          String remark, List<TransferLine> lines, String createdBy) {
        if (fromWarehouseId == toWarehouseId) {
            throw new IllegalArgumentException("调出仓与调入仓不能相同（同仓调拨无意义）: 仓库 " + fromWarehouseId);
        }
        Objects.requireNonNull(lines, "调拨行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("调拨单至少要有一行");
        }
        List<Integer> lineNos = lines.stream().map(TransferLine::getLineNo).distinct().toList();
        if (lineNos.size() != lines.size()) {
            throw new IllegalArgumentException("调拨单行号不能重复");
        }
        return new TransferDocument(docNo, fromWarehouseId, toWarehouseId, remark, lines, createdBy);
    }

    /**
     * 持久层重建工厂（不重跑业务校验）：用既有状态恢复单据。
     *
     * @param status 落库的单据状态
     */
    public static TransferDocument restore(String docNo, long fromWarehouseId, long toWarehouseId,
                                           String remark, DocumentStatus status, List<TransferLine> lines,
                                           String createdBy) {
        TransferDocument document = new TransferDocument(docNo, fromWarehouseId, toWarehouseId,
                remark, lines, createdBy);
        document.restoreStatus(status);
        return document;
    }

    public long getFromWarehouseId() {
        return fromWarehouseId;
    }

    public long getToWarehouseId() {
        return toWarehouseId;
    }

    public String getRemark() {
        return remark;
    }

    /** 行项目只读视图（不可变，防外部直接增删行） */
    public List<TransferLine> getLines() {
        return List.copyOf(lines);
    }

    // ---------------------------------------------------------------
    // AuditTarget（审计切面从 @Audited 写方法返回值提取目标标识与摘要）
    // ---------------------------------------------------------------

    @Override
    public Long auditTargetId() {
        // 调拨单无数据库自增 id 暴露（聚合以单据号为业务键），统一返回 null
        return null;
    }

    @Override
    public String auditTargetCode() {
        return getDocNo();
    }

    @Override
    public String auditSummary() {
        return "调出仓=" + fromWarehouseId + ", 调入仓=" + toWarehouseId + ", 状态=" + getStatus()
                + ", 行数=" + lines.size() + ", 说明=" + AuditTarget.text(remark);
    }
}
