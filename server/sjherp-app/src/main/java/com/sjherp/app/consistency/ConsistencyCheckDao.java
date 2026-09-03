package com.sjherp.app.consistency;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一致性校验只读 DAO（M3-T13，<b>只读</b>，参照 {@code InventoryQueryDao}）。
 *
 * <p>CLAUDE.md 铁律「写操作只能经领域服务，报表/校验只读除外」——本类只有 SELECT/聚合，
 * 零 INSERT/UPDATE/DELETE。所有比对在 {@link ConsistencyCheckService} 用 BigDecimal#compareTo 完成，
 * 本类只负责把聚合行装载为 record（tenant_id 恒 0，ADR-002）。
 *
 * <p>FULL JOIN 在 MySQL 不可用——库存数量/成本恒等式用 LEFT JOIN 两向 UNION 实现，
 * 保证「流水有而余额无」与「余额有而流水无」两类孤儿都能被覆盖。
 */
@Repository
public class ConsistencyCheckDao {

    /** 库存维度聚合行：每个(仓库,商品) 的 Σ流水数量/Σ流水金额 与 余额数量/余额金额（缺侧补 0）。 */
    public record InventoryLedgerRow(long warehouseId, long productId,
                                     BigDecimal txnQuantitySum, BigDecimal txnCostSum,
                                     BigDecimal balanceQuantity, BigDecimal balanceCostAmount) {
    }

    /** 库存余额行（规则3 非负校验用）。 */
    public record BalanceRow(long warehouseId, long productId,
                             BigDecimal quantity, BigDecimal costAmount) {
    }

    /** 已过账采购发票额 vs 应付额（按发票号勾稽）。payableAmount 为 null 表示无应付行。 */
    public record PayableMatchRow(String invoiceNo, BigDecimal invoiceAmount, BigDecimal payableAmount) {
    }

    /** 已过账销售发票额 vs 应收额（按发票号勾稽）。receivableAmount 为 null 表示无应收行。 */
    public record ReceivableMatchRow(String invoiceNo, BigDecimal invoiceAmount,
                                     BigDecimal receivableAmount) {
    }

    /** 出库行 COGS vs 该出库单 SALES_OUT 流水金额合计（绝对值）。salesOutCostSum 为 null 表示无对应流水。 */
    public record CogsMatchRow(String deliveryNo, int lineNo, BigDecimal cogsAmount,
                               BigDecimal salesOutCostSum) {
    }

    /** 采购三单数量勾稽：订单量 / 已收量 / 已开票量（按采购订单号 + 商品聚合）。 */
    public record PurchaseThreeWayRow(String orderNo, long productId, BigDecimal orderedQty,
                                      BigDecimal receivedQty, BigDecimal invoicedQty) {
    }

    /** 销售三单数量勾稽：订单量 / 已发量 / 已开票量（按销售订单号 + 商品聚合）。 */
    public record SalesThreeWayRow(String orderNo, long productId, BigDecimal orderedQty,
                                   BigDecimal deliveredQty, BigDecimal invoicedQty) {
    }

    /**
     * 核销 rollup 勾稽行（M4-T04c，应收/应付各一条/笔）。
     *
     * <p>{@code settlementType} 为 {@code "RECEIVABLE"}/{@code "PAYABLE"}（来自查询常量，仅用于差异定位文案）；
     * {@code targetId} 为子账主键、{@code sourceDocNo} 为来源单号（发票号，做 break.key）；
     * {@code amount} 应收/应付总额、{@code settledAmount} 子账已核销额（rollup 维护值）、{@code status} 子账状态；
     * {@code recordSettledSum} 为 settlement_record 按 type+target 聚合的 Σamount（核销真源；无核销记录时 LEFT JOIN 收敛为 0）。
     */
    public record SettlementRollupRow(String settlementType, long targetId, String sourceDocNo,
                                      BigDecimal amount, BigDecimal settledAmount, String status,
                                      BigDecimal recordSettledSum) {
    }

    /**
     * 已完工工单工费结转覆盖行（M5-T06 规则11，D9，WARN 非阻塞）。
     *
     * <p>{@code workOrderDocNo} 为有完工产出（completed_qty>0）的工单号；
     * {@code completedQty} 为工单累计完工量；{@code settlementLineCount} 为引用该工单的
     * 已过账（COMPLETED）成本结转单行数（0 表示工费尚未结转）。
     */
    public record WorkOrderCostSettledRow(String workOrderDocNo, BigDecimal completedQty,
                                          long settlementLineCount) {
    }

    /**
     * 领料行成本勾稽行（M5-T08 规则12 领料侧）：COMPLETED 领料单行 {@code issuedCost}（正数口径）
     * 与该领料单行 PRODUCTION_ISSUE 库存流水 Σ的相反数（{@code issueTxnCostSum}，流水 total_cost 为负，
     * SQL 取 −SUM 转正）。{@code issueTxnCostSum} 为 null 表示无对应出库流水（缺流水 → 规则12 ERROR）。
     */
    public record MaterialIssueCostRow(String docNo, int lineNo, BigDecimal issuedCost,
                                       BigDecimal issueTxnCostSum) {
    }

    /**
     * 退料行成本勾稽行（M5-T08 规则12 退料侧）：COMPLETED 退料单行 {@code returnedCost}（正数口径）
     * 与该退料单行 PRODUCTION_RETURN 库存流水 Σtotal_cost（{@code returnTxnCostSum}，入库为正）。
     * {@code returnTxnCostSum} 为 null 表示无对应入库流水（缺流水 → 规则12 ERROR）。
     */
    public record MaterialReturnCostRow(String docNo, int lineNo, BigDecimal returnedCost,
                                        BigDecimal returnTxnCostSum) {
    }

    /**
     * 完工入库成本勾稽行（M5-T08 规则13）：COMPLETED 报工单 {@code inboundCost}（正数口径）
     * 与该报工单 PRODUCTION_IN 库存流水 Σtotal_cost（{@code productionInCostSum}）。
     * {@code productionInCostSum} 为 null 表示无对应入库流水（缺流水 → 规则13 ERROR）。
     */
    public record ProductionInboundCostRow(String docNo, BigDecimal inboundCost,
                                           BigDecimal productionInCostSum) {
    }

    /**
     * 工单料费守恒行（M5-T08 规则14）：按工单聚合的 Σ完工入库料金额（{@code inboundSum}）、
     * Σ COMPLETED 领料 issued_cost（{@code issuedSum}）、Σ COMPLETED 退料 returned_cost（{@code returnedSum}）。
     * 比对时净领料 = issuedSum − returnedSum，与 inboundSum 守恒判定（缺侧 COALESCE 收敛 0）。
     */
    public record WorkOrderMaterialRow(String workOrderDocNo, BigDecimal inboundSum,
                                       BigDecimal issuedSum, BigDecimal returnedSum) {
    }

    /**
     * 工单完工量勾稽行（M5-T08 规则15）：工单 {@code completedQty}（recordCompletion 累加回写值）
     * 与 Σ该工单已过账 COMPLETED 报工 completed_qty（{@code reportCompletedQtySum}）。
     */
    public record WorkOrderCompletedQtyRow(String workOrderDocNo, BigDecimal completedQty,
                                           BigDecimal reportCompletedQtySum) {
    }

    /**
     * 成本结转工费追加勾稽行（M5-T08 规则16）：COMPLETED 成本结转单行的工费增量原值
     * （{@code expectedIncrement} = completed_cost − material_cost − already_transferred，可 ≤0）
     * 与该结转行 COST_ADJUST 库存流水 Σtotal_cost（{@code costAdjustSum}，COALESCE 收敛 0）。
     * 比对时增量截 0 下限（过账仅在增量&gt;0 时出 COST_ADJUST 流水）。
     */
    public record CostSettlementAdjustRow(String docNo, int lineNo, String workOrderDocNo,
                                          BigDecimal expectedIncrement, BigDecimal costAdjustSum) {
    }

    /** 工单生产库存累计成本（PRODUCTION_IN+COST_ADJUST）与生产成本凭证1405净借方。 */
    public record ProductionInventoryGlRow(String workOrderDocNo, BigDecimal productionInventoryCost,
                                           BigDecimal glInventoryDebit) {
    }

    private static final RowMapper<InventoryLedgerRow> LEDGER_MAPPER = (rs, n) -> new InventoryLedgerRow(
            rs.getLong("warehouse_id"), rs.getLong("product_id"),
            nz(rs.getBigDecimal("txn_qty_sum")), nz(rs.getBigDecimal("txn_cost_sum")),
            nz(rs.getBigDecimal("balance_qty")), nz(rs.getBigDecimal("balance_cost")));

    private static final RowMapper<BalanceRow> BALANCE_MAPPER = (rs, n) -> new BalanceRow(
            rs.getLong("warehouse_id"), rs.getLong("product_id"),
            rs.getBigDecimal("quantity"), rs.getBigDecimal("cost_amount"));

    private static final RowMapper<PayableMatchRow> PAYABLE_MAPPER = (rs, n) -> new PayableMatchRow(
            rs.getString("invoice_no"), rs.getBigDecimal("invoice_amount"),
            rs.getBigDecimal("payable_amount"));

    private static final RowMapper<ReceivableMatchRow> RECEIVABLE_MAPPER = (rs, n) -> new ReceivableMatchRow(
            rs.getString("invoice_no"), rs.getBigDecimal("invoice_amount"),
            rs.getBigDecimal("receivable_amount"));

    private static final RowMapper<CogsMatchRow> COGS_MAPPER = (rs, n) -> new CogsMatchRow(
            rs.getString("delivery_no"), rs.getInt("line_no"),
            rs.getBigDecimal("cogs_amount"), rs.getBigDecimal("sales_out_cost_sum"));

    private static final RowMapper<PurchaseThreeWayRow> PURCHASE_3W_MAPPER = (rs, n) -> new PurchaseThreeWayRow(
            rs.getString("order_no"), rs.getLong("product_id"),
            nz(rs.getBigDecimal("ordered_qty")), nz(rs.getBigDecimal("received_qty")),
            nz(rs.getBigDecimal("invoiced_qty")));

    private static final RowMapper<SalesThreeWayRow> SALES_3W_MAPPER = (rs, n) -> new SalesThreeWayRow(
            rs.getString("order_no"), rs.getLong("product_id"),
            nz(rs.getBigDecimal("ordered_qty")), nz(rs.getBigDecimal("delivered_qty")),
            nz(rs.getBigDecimal("invoiced_qty")));

    // amount/settled_amount NOT NULL，不收敛；record_settled_sum 经 COALESCE 已为 0（无核销记录时）
    private static final RowMapper<SettlementRollupRow> SETTLEMENT_ROLLUP_MAPPER = (rs, n) -> new SettlementRollupRow(
            rs.getString("settlement_type"), rs.getLong("target_id"), rs.getString("source_doc_no"),
            rs.getBigDecimal("amount"), rs.getBigDecimal("settled_amount"), rs.getString("status"),
            nz(rs.getBigDecimal("record_settled_sum")));

    private final JdbcTemplate jdbc;

    public ConsistencyCheckDao(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不能为空");
    }

    // ---------------------------------------------------------------
    // 规则1/2：库存流水Σ vs 余额（LEFT JOIN 两向 UNION，覆盖两类孤儿行）
    // ---------------------------------------------------------------

    /**
     * 每个(仓库,商品) 的 Σ流水数量/Σ流水金额 与 余额数量/余额金额。
     * 用「流水侧聚合 LEFT JOIN 余额」UNION「余额 LEFT JOIN 流水侧聚合中缺失的维度」覆盖全集。
     */
    @Transactional(readOnly = true)
    public List<InventoryLedgerRow> inventoryLedger() {
        String txnAgg = "(SELECT warehouse_id, product_id, "
                + "SUM(quantity) AS qty_sum, SUM(total_cost) AS cost_sum "
                + "FROM inventory_transaction WHERE tenant_id = 0 "
                + "GROUP BY warehouse_id, product_id) t";
        String bal = "(SELECT warehouse_id, product_id, quantity, cost_amount "
                + "FROM inventory_balance WHERE tenant_id = 0) b";

        // 左：流水侧为主，带上对应余额（余额缺失则 NULL→0）
        String left = "SELECT t.warehouse_id, t.product_id, "
                + "t.qty_sum AS txn_qty_sum, t.cost_sum AS txn_cost_sum, "
                + "b.quantity AS balance_qty, b.cost_amount AS balance_cost "
                + "FROM " + txnAgg + " LEFT JOIN " + bal
                + " ON b.warehouse_id = t.warehouse_id AND b.product_id = t.product_id";
        // 右：余额侧为主、仅取流水缺失的维度（避免与左侧重复），流水 NULL→0
        String rightOnlyOrphans = "SELECT b.warehouse_id, b.product_id, "
                + "0 AS txn_qty_sum, 0 AS txn_cost_sum, "
                + "b.quantity AS balance_qty, b.cost_amount AS balance_cost "
                + "FROM " + bal + " LEFT JOIN " + txnAgg
                + " ON t.warehouse_id = b.warehouse_id AND t.product_id = b.product_id "
                + "WHERE t.warehouse_id IS NULL";

        return jdbc.query(left + " UNION ALL " + rightOnlyOrphans, LEDGER_MAPPER);
    }

    // ---------------------------------------------------------------
    // 规则3：库存余额非负（只取数量<0 或 金额<0 的行——干净库无负行返回空）
    // ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<BalanceRow> negativeBalances() {
        return jdbc.query("SELECT warehouse_id, product_id, quantity, cost_amount "
                + "FROM inventory_balance WHERE tenant_id = 0 "
                + "AND (quantity < 0 OR cost_amount < 0) "
                + "ORDER BY warehouse_id, product_id", BALANCE_MAPPER);
    }

    // ---------------------------------------------------------------
    // 规则4：已过账(COMPLETED)采购发票额 = 应付额（按发票号）
    // ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PayableMatchRow> payableMatches() {
        return jdbc.query("SELECT pi.doc_no AS invoice_no, "
                + "(SELECT COALESCE(SUM(pil.amount), 0) FROM purchase_invoice_line pil "
                + " WHERE pil.purchase_invoice_id = pi.id) AS invoice_amount, "
                + "ap.amount AS payable_amount "
                + "FROM purchase_invoice pi "
                // M4-T07b：排除已冲销应付（红冲后子账与发票一并 REVERSED；发票 REVERSED 已被下方
                // status='COMPLETED' 过滤剔除，此处再排除 REVERSED 应付兜底，避免红冲对误报勾稽 ERROR）
                + "LEFT JOIN accounts_payable ap ON ap.tenant_id = 0 AND ap.source_doc_no = pi.doc_no "
                + " AND ap.status <> 'REVERSED' "
                + "WHERE pi.tenant_id = 0 AND pi.status = 'COMPLETED' "
                + "ORDER BY pi.id", PAYABLE_MAPPER);
    }

    // ---------------------------------------------------------------
    // 规则5：已过账销售发票额 = 应收额（按发票号）
    // ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ReceivableMatchRow> receivableMatches() {
        return jdbc.query("SELECT si.doc_no AS invoice_no, "
                + "(SELECT COALESCE(SUM(sil.amount), 0) FROM sales_invoice_line sil "
                + " WHERE sil.sales_invoice_id = si.id) AS invoice_amount, "
                + "ar.amount AS receivable_amount "
                + "FROM sales_invoice si "
                // M4-T07b：排除已冲销应收（红冲后子账与发票一并 REVERSED；同采购对称兜底）
                + "LEFT JOIN accounts_receivable ar ON ar.tenant_id = 0 AND ar.source_doc_no = si.doc_no "
                + " AND ar.status <> 'REVERSED' "
                + "WHERE si.tenant_id = 0 AND si.status = 'COMPLETED' "
                + "ORDER BY si.id", RECEIVABLE_MAPPER);
    }

    // ---------------------------------------------------------------
    // 规则6：出库行 COGS = 该出库单 SALES_OUT 流水金额合计（绝对值）
    // 出库流水 src_doc_no = 出库单号、src_line_no = 出库行号；total_cost 为负，取 -SUM
    // ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<CogsMatchRow> cogsMatches() {
        return jdbc.query("SELECT sd.doc_no AS delivery_no, sdl.line_no AS line_no, "
                + "sdl.cogs_amount AS cogs_amount, "
                + "(SELECT -SUM(it.total_cost) FROM inventory_transaction it "
                + " WHERE it.tenant_id = 0 AND it.txn_type = 'SALES_OUT' "
                + " AND it.src_doc_no = sd.doc_no AND it.src_line_no = sdl.line_no) AS sales_out_cost_sum "
                + "FROM sales_delivery sd "
                + "JOIN sales_delivery_line sdl ON sdl.sales_delivery_id = sd.id "
                + "WHERE sd.tenant_id = 0 AND sd.status = 'COMPLETED' "
                + "ORDER BY sd.id, sdl.line_no", COGS_MAPPER);
    }

    // ---------------------------------------------------------------
    // 规则7：采购三单数量勾稽（按采购订单号 + 商品：订单量/已收量/已开票量）
    // 已收量 = Σ已过账(COMPLETED)入库单行 quantity；已开票量 = Σ已过账采购发票行 quantity
    // ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PurchaseThreeWayRow> purchaseThreeWay() {
        return jdbc.query("SELECT po.doc_no AS order_no, pol.product_id AS product_id, "
                + "SUM(pol.quantity) AS ordered_qty, "
                + "(SELECT COALESCE(SUM(prl.quantity), 0) FROM purchase_receipt pr "
                + " JOIN purchase_receipt_line prl ON prl.purchase_receipt_id = pr.id "
                + " WHERE pr.tenant_id = 0 AND pr.status = 'COMPLETED' "
                + "   AND pr.purchase_order_no = po.doc_no AND prl.product_id = pol.product_id) AS received_qty, "
                + "(SELECT COALESCE(SUM(pil.quantity), 0) FROM purchase_invoice pi "
                + " JOIN purchase_invoice_line pil ON pil.purchase_invoice_id = pi.id "
                + " JOIN purchase_receipt pr2 ON pr2.tenant_id = 0 AND pr2.doc_no = pi.purchase_receipt_no "
                + " WHERE pi.tenant_id = 0 AND pi.status = 'COMPLETED' "
                + "   AND pr2.purchase_order_no = po.doc_no AND pil.product_id = pol.product_id) AS invoiced_qty "
                + "FROM purchase_order po "
                + "JOIN purchase_order_line pol ON pol.purchase_order_id = po.id "
                + "WHERE po.tenant_id = 0 "
                + "GROUP BY po.doc_no, pol.product_id, po.id "
                + "ORDER BY po.id, pol.product_id", PURCHASE_3W_MAPPER);
    }

    // ---------------------------------------------------------------
    // 规则7：销售三单数量勾稽（按销售订单号 + 商品：订单量/已发量/已开票量）
    // 已发量 = Σ已过账出库单行 quantity；已开票量 = Σ已过账销售发票行 quantity
    // ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<SalesThreeWayRow> salesThreeWay() {
        return jdbc.query("SELECT so.doc_no AS order_no, sol.product_id AS product_id, "
                + "SUM(sol.quantity) AS ordered_qty, "
                + "(SELECT COALESCE(SUM(sdl.quantity), 0) FROM sales_delivery sd "
                + " JOIN sales_delivery_line sdl ON sdl.sales_delivery_id = sd.id "
                + " WHERE sd.tenant_id = 0 AND sd.status = 'COMPLETED' "
                + "   AND sd.sales_order_no = so.doc_no AND sdl.product_id = sol.product_id) AS delivered_qty, "
                + "(SELECT COALESCE(SUM(sil.quantity), 0) FROM sales_invoice si "
                + " JOIN sales_invoice_line sil ON sil.sales_invoice_id = si.id "
                + " JOIN sales_delivery sd2 ON sd2.tenant_id = 0 AND sd2.doc_no = si.sales_delivery_no "
                + " WHERE si.tenant_id = 0 AND si.status = 'COMPLETED' "
                + "   AND sd2.sales_order_no = so.doc_no AND sil.product_id = sol.product_id) AS invoiced_qty "
                + "FROM sales_order so "
                + "JOIN sales_order_line sol ON sol.sales_order_id = so.id "
                + "WHERE so.tenant_id = 0 "
                + "GROUP BY so.doc_no, sol.product_id, so.id "
                + "ORDER BY so.id, sol.product_id", SALES_3W_MAPPER);
    }

    // ---------------------------------------------------------------
    // 规则8/9/10（M4-T04c）：核销 rollup / 无超额 / 状态-余额 一致
    // 子账（accounts_receivable / accounts_payable）逐行 LEFT JOIN settlement_record（按 type+target 聚合 Σamount）。
    // LEFT JOIN：即便子账无任何核销记录（OPEN）也保留该行，record_settled_sum 经 COALESCE 收敛为 0，
    // 使「子账 settled_amount 非 0 但无核销记录」这类孤儿不一致也能被规则8 暴露（不静默丢行）。
    // 既有规则4/5（amount==发票额，amount 不可变）不动；本组只比对 settled_amount/status 与核销记录真源。
    // ---------------------------------------------------------------

    /** 应收核销 rollup 行（每笔应收一行）：子账 amount/settled_amount/status 与核销记录 Σ（type=RECEIVABLE）。 */
    @Transactional(readOnly = true)
    public List<SettlementRollupRow> receivableRollups() {
        return jdbc.query("SELECT 'RECEIVABLE' AS settlement_type, ar.id AS target_id, "
                + "ar.source_doc_no AS source_doc_no, ar.amount AS amount, "
                + "ar.settled_amount AS settled_amount, ar.status AS status, "
                + "(SELECT COALESCE(SUM(sr.amount), 0) FROM settlement_record sr "
                + " WHERE sr.tenant_id = 0 AND sr.settlement_type = 'RECEIVABLE' "
                + "   AND sr.target_id = ar.id) AS record_settled_sum "
                + "FROM accounts_receivable ar "
                + "WHERE ar.tenant_id = 0 "
                + "ORDER BY ar.id", SETTLEMENT_ROLLUP_MAPPER);
    }

    /** 应付核销 rollup 行（每笔应付一行）：子账 amount/settled_amount/status 与核销记录 Σ（type=PAYABLE）。 */
    @Transactional(readOnly = true)
    public List<SettlementRollupRow> payableRollups() {
        return jdbc.query("SELECT 'PAYABLE' AS settlement_type, ap.id AS target_id, "
                + "ap.source_doc_no AS source_doc_no, ap.amount AS amount, "
                + "ap.settled_amount AS settled_amount, ap.status AS status, "
                + "(SELECT COALESCE(SUM(sr.amount), 0) FROM settlement_record sr "
                + " WHERE sr.tenant_id = 0 AND sr.settlement_type = 'PAYABLE' "
                + "   AND sr.target_id = ap.id) AS record_settled_sum "
                + "FROM accounts_payable ap "
                + "WHERE ap.tenant_id = 0 "
                + "ORDER BY ap.id", SETTLEMENT_ROLLUP_MAPPER);
    }

    // ---------------------------------------------------------------
    // 规则11（M5-T06，D9）：已完工工单的工费已结转（completed_qty>0 但无 COMPLETED 成本结转行 → WARN）
    // ---------------------------------------------------------------

    private static final RowMapper<WorkOrderCostSettledRow> WO_COST_SETTLED_MAPPER =
            (rs, n) -> new WorkOrderCostSettledRow(
                    rs.getString("doc_no"),
                    nz(rs.getBigDecimal("completed_qty")),
                    rs.getLong("settlement_line_count"));

    /**
     * 有完工产出（completed_qty>0）且状态 EXECUTING/COMPLETED 的工单，及其已过账成本结转行数。
     * 结转行数=0 表示该工单完工工费尚未结转（规则11 WARN）。
     */
    @Transactional(readOnly = true)
    public List<WorkOrderCostSettledRow> workOrderCostSettled() {
        return jdbc.query("SELECT wo.doc_no AS doc_no, wo.completed_qty AS completed_qty, "
                + "(SELECT COUNT(*) FROM production_cost_settlement_line pl "
                + " JOIN production_cost_settlement ph ON ph.id = pl.settlement_id "
                + "  AND ph.tenant_id = pl.tenant_id "
                + " WHERE pl.tenant_id = 0 AND pl.work_order_doc_no = wo.doc_no "
                + "   AND ph.status = 'COMPLETED') AS settlement_line_count "
                + "FROM work_order wo "
                + "WHERE wo.tenant_id = 0 AND wo.completed_qty > 0 "
                + "  AND wo.status IN ('EXECUTING', 'COMPLETED') "
                + "ORDER BY wo.id", WO_COST_SETTLED_MAPPER);
    }

    // ===============================================================
    // 规则12–16（M5-T08 生产链全链路勾稽）
    // ===============================================================

    private static final RowMapper<MaterialIssueCostRow> MATERIAL_ISSUE_COST_MAPPER =
            (rs, n) -> new MaterialIssueCostRow(
                    rs.getString("doc_no"), rs.getInt("line_no"),
                    nz(rs.getBigDecimal("issued_cost")), rs.getBigDecimal("issue_txn_cost_sum"));

    private static final RowMapper<MaterialReturnCostRow> MATERIAL_RETURN_COST_MAPPER =
            (rs, n) -> new MaterialReturnCostRow(
                    rs.getString("doc_no"), rs.getInt("line_no"),
                    nz(rs.getBigDecimal("returned_cost")), rs.getBigDecimal("return_txn_cost_sum"));

    private static final RowMapper<ProductionInboundCostRow> PRODUCTION_INBOUND_COST_MAPPER =
            (rs, n) -> new ProductionInboundCostRow(
                    rs.getString("doc_no"), nz(rs.getBigDecimal("inbound_cost")),
                    rs.getBigDecimal("production_in_cost_sum"));

    private static final RowMapper<WorkOrderMaterialRow> WO_MATERIAL_MAPPER =
            (rs, n) -> new WorkOrderMaterialRow(
                    rs.getString("doc_no"), nz(rs.getBigDecimal("inbound_sum")),
                    nz(rs.getBigDecimal("issued_sum")), nz(rs.getBigDecimal("returned_sum")));

    private static final RowMapper<WorkOrderCompletedQtyRow> WO_COMPLETED_QTY_MAPPER =
            (rs, n) -> new WorkOrderCompletedQtyRow(
                    rs.getString("doc_no"), nz(rs.getBigDecimal("completed_qty")),
                    nz(rs.getBigDecimal("report_completed_qty_sum")));

    private static final RowMapper<CostSettlementAdjustRow> COST_SETTLEMENT_ADJUST_MAPPER =
            (rs, n) -> new CostSettlementAdjustRow(
                    rs.getString("doc_no"), rs.getInt("line_no"), rs.getString("work_order_doc_no"),
                    nz(rs.getBigDecimal("expected_increment")), nz(rs.getBigDecimal("cost_adjust_sum")));

    private static final RowMapper<ProductionInventoryGlRow> PRODUCTION_INVENTORY_GL_MAPPER =
            (rs, n) -> new ProductionInventoryGlRow(
                    rs.getString("work_order_doc_no"),
                    nz(rs.getBigDecimal("production_inventory_cost")),
                    nz(rs.getBigDecimal("gl_inventory_debit")));

    /**
     * 规则12 领料侧：每张 COMPLETED 领料单行 issued_cost 与该行 PRODUCTION_ISSUE 流水 −Σtotal_cost。
     * 出库流水 total_cost 为负，SQL 取 −SUM 转正口径与 issued_cost 对齐；无流水时子查询返回 NULL（缺流水 ERROR）。
     */
    @Transactional(readOnly = true)
    public List<MaterialIssueCostRow> materialIssueCostMatches() {
        return jdbc.query("SELECT mi.doc_no AS doc_no, mil.line_no AS line_no, "
                + "mil.issued_cost AS issued_cost, "
                + "(SELECT -SUM(it.total_cost) FROM inventory_transaction it "
                + " WHERE it.tenant_id = 0 AND it.txn_type = 'PRODUCTION_ISSUE' "
                // src_doc_no（inventory_transaction，utf8mb4_0900_ai_ci）vs doc_no（生产表，utf8mb4_unicode_ci）
                // 跨列比较须显式 COLLATE 统一，否则 MySQL error 1267 Illegal mix of collations（CI integration-db 教训）
                + "   AND it.src_doc_no = mi.doc_no COLLATE utf8mb4_unicode_ci AND it.src_line_no = mil.line_no) AS issue_txn_cost_sum "
                + "FROM material_issue mi "
                + "JOIN material_issue_line mil ON mil.material_issue_id = mi.id "
                + "WHERE mi.tenant_id = 0 AND mi.status = 'COMPLETED' "
                + "ORDER BY mi.id, mil.line_no", MATERIAL_ISSUE_COST_MAPPER);
    }

    /**
     * 规则12 退料侧：每张 COMPLETED 退料单行 returned_cost 与该行 PRODUCTION_RETURN 流水 Σtotal_cost（入库为正）。
     * 无流水时子查询返回 NULL（缺流水 ERROR）。
     */
    @Transactional(readOnly = true)
    public List<MaterialReturnCostRow> materialReturnCostMatches() {
        return jdbc.query("SELECT mr.doc_no AS doc_no, mrl.line_no AS line_no, "
                + "mrl.returned_cost AS returned_cost, "
                + "(SELECT SUM(it.total_cost) FROM inventory_transaction it "
                + " WHERE it.tenant_id = 0 AND it.txn_type = 'PRODUCTION_RETURN' "
                + "   AND it.src_doc_no = mr.doc_no COLLATE utf8mb4_unicode_ci AND it.src_line_no = mrl.line_no) AS return_txn_cost_sum "
                + "FROM material_return mr "
                + "JOIN material_return_line mrl ON mrl.material_return_id = mr.id "
                + "WHERE mr.tenant_id = 0 AND mr.status = 'COMPLETED' "
                + "ORDER BY mr.id, mrl.line_no", MATERIAL_RETURN_COST_MAPPER);
    }

    /**
     * 规则13：每张 COMPLETED 报工单 inbound_cost 与该报工单 PRODUCTION_IN 流水 Σtotal_cost（入库为正，单行固定行号 1）。
     * 无流水时子查询返回 NULL（缺流水 ERROR）。
     */
    @Transactional(readOnly = true)
    public List<ProductionInboundCostRow> productionInboundCostMatches() {
        return jdbc.query("SELECT pr.doc_no AS doc_no, pr.inbound_cost AS inbound_cost, "
                + "(SELECT SUM(it.total_cost) FROM inventory_transaction it "
                + " WHERE it.tenant_id = 0 AND it.txn_type = 'PRODUCTION_IN' "
                + "   AND it.src_doc_no = pr.doc_no COLLATE utf8mb4_unicode_ci) AS production_in_cost_sum "
                + "FROM production_report pr "
                + "WHERE pr.tenant_id = 0 AND pr.status = 'COMPLETED' "
                + "ORDER BY pr.id", PRODUCTION_INBOUND_COST_MAPPER);
    }

    /**
     * 规则14：按工单聚合 Σ完工入库料金额（COMPLETED 报工 inbound_cost）、Σ COMPLETED 领料 issued_cost、
     * Σ COMPLETED 退料 returned_cost（退料经 material_issue_doc_no→原领料单 work_order_doc_no 归属工单）。
     * 主表 work_order LEFT JOIN（子查询 COALESCE 收敛 0）不丢任何工单行。
     */
    @Transactional(readOnly = true)
    public List<WorkOrderMaterialRow> workOrderMaterialConservation() {
        return jdbc.query("SELECT wo.doc_no AS doc_no, "
                + "(SELECT COALESCE(SUM(pr.inbound_cost), 0) FROM production_report pr "
                + " WHERE pr.tenant_id = 0 AND pr.status = 'COMPLETED' "
                + "   AND pr.work_order_doc_no = wo.doc_no) AS inbound_sum, "
                + "(SELECT COALESCE(SUM(mil.issued_cost), 0) FROM material_issue mi "
                + " JOIN material_issue_line mil ON mil.material_issue_id = mi.id "
                + " WHERE mi.tenant_id = 0 AND mi.status = 'COMPLETED' "
                + "   AND mi.work_order_doc_no = wo.doc_no) AS issued_sum, "
                + "(SELECT COALESCE(SUM(mrl.returned_cost), 0) FROM material_return mr "
                + " JOIN material_return_line mrl ON mrl.material_return_id = mr.id "
                + " JOIN material_issue mi2 ON mi2.tenant_id = 0 AND mi2.doc_no = mr.material_issue_doc_no "
                + " WHERE mr.tenant_id = 0 AND mr.status = 'COMPLETED' "
                + "   AND mi2.work_order_doc_no = wo.doc_no) AS returned_sum "
                + "FROM work_order wo "
                + "WHERE wo.tenant_id = 0 "
                + "ORDER BY wo.id", WO_MATERIAL_MAPPER);
    }

    /**
     * 规则15：每工单 completed_qty 与 Σ该工单 COMPLETED 报工 completed_qty（主表 work_order，子查询 COALESCE 0）。
     */
    @Transactional(readOnly = true)
    public List<WorkOrderCompletedQtyRow> workOrderCompletedQty() {
        return jdbc.query("SELECT wo.doc_no AS doc_no, wo.completed_qty AS completed_qty, "
                + "(SELECT COALESCE(SUM(pr.completed_qty), 0) FROM production_report pr "
                + " WHERE pr.tenant_id = 0 AND pr.status = 'COMPLETED' "
                + "   AND pr.work_order_doc_no = wo.doc_no) AS report_completed_qty_sum "
                + "FROM work_order wo "
                + "WHERE wo.tenant_id = 0 "
                + "ORDER BY wo.id", WO_COMPLETED_QTY_MAPPER);
    }

    /**
     * 规则16：每张 COMPLETED 成本结转单行的工费增量（completed_cost − material_cost − already_transferred）
     * 与该结转行 COST_ADJUST 流水 Σtotal_cost（COALESCE 0）。增量原值（可 ≤0）在比对层截 0 下限。
     */
    @Transactional(readOnly = true)
    public List<CostSettlementAdjustRow> costSettlementAdjustMatches() {
        return jdbc.query("SELECT ph.doc_no AS doc_no, pl.line_no AS line_no, "
                + "pl.work_order_doc_no AS work_order_doc_no, "
                + "(pl.completed_cost - pl.material_cost - pl.already_transferred) AS expected_increment, "
                + "(SELECT COALESCE(SUM(it.total_cost), 0) FROM inventory_transaction it "
                + " WHERE it.tenant_id = 0 AND it.txn_type = 'COST_ADJUST' "
                + "   AND it.src_doc_no = ph.doc_no COLLATE utf8mb4_unicode_ci AND it.src_line_no = pl.line_no) AS cost_adjust_sum "
                + "FROM production_cost_settlement ph "
                + "JOIN production_cost_settlement_line pl ON pl.settlement_id = ph.id "
                + "WHERE ph.tenant_id = 0 AND ph.status = 'COMPLETED' "
                + "ORDER BY ph.id, pl.line_no", COST_SETTLEMENT_ADJUST_MAPPER);
    }

    /** SUM/列可能为 NULL（无对应行），统一收敛为 0。 */
    /**
     * 规则17：工单生产库存成本 = 已过账报工 PRODUCTION_IN + 已过账结转 COST_ADJUST；
     * 总账侧取同一工单生产成本结算凭证的 1405 净借方（借减贷，包含凭证红冲）。
     */
    @Transactional(readOnly = true)
    public List<ProductionInventoryGlRow> productionInventoryGlMatches() {
        return jdbc.query("SELECT x.work_order_doc_no AS work_order_doc_no, "
                + "(SELECT COALESCE(SUM(it.total_cost), 0) FROM inventory_transaction it "
                + " JOIN production_report pr ON pr.tenant_id = 0 "
                + "  AND it.src_doc_no = pr.doc_no COLLATE utf8mb4_unicode_ci "
                + " WHERE it.tenant_id = 0 AND it.txn_type = 'PRODUCTION_IN' "
                + "   AND pr.status = 'COMPLETED' AND pr.work_order_doc_no = x.work_order_doc_no) "
                + "+ (SELECT COALESCE(SUM(it.total_cost), 0) FROM inventory_transaction it "
                + " JOIN production_cost_settlement ph ON ph.tenant_id = 0 "
                + "  AND it.src_doc_no = ph.doc_no COLLATE utf8mb4_unicode_ci "
                + " JOIN production_cost_settlement_line pl ON pl.tenant_id = ph.tenant_id "
                + "  AND pl.settlement_id = ph.id AND it.src_line_no = pl.line_no "
                + " WHERE it.tenant_id = 0 AND it.txn_type = 'COST_ADJUST' "
                + "   AND ph.status = 'COMPLETED' AND pl.work_order_doc_no = x.work_order_doc_no) "
                + " AS production_inventory_cost, "
                + "(SELECT COALESCE(SUM(vl.debit - vl.credit), 0) "
                + " FROM production_cost_settlement ph "
                + " JOIN production_cost_settlement_line pl ON pl.tenant_id = ph.tenant_id "
                + "  AND pl.settlement_id = ph.id "
                + " JOIN voucher origin_v ON origin_v.tenant_id = 0 "
                + "  AND origin_v.source_doc_type = 'PRODUCTION_COST_SETTLEMENT' "
                + "  AND origin_v.source_doc_no COLLATE utf8mb4_unicode_ci "
                + "      = CONCAT(ph.doc_no, ':', pl.work_order_doc_no) "
                + " JOIN voucher related_v ON related_v.tenant_id = origin_v.tenant_id "
                + "  AND (related_v.id = origin_v.id OR related_v.reversal_of_id = origin_v.doc_no COLLATE utf8mb4_unicode_ci) "
                + " JOIN voucher_line vl ON vl.tenant_id = related_v.tenant_id AND vl.voucher_id = related_v.id "
                + " WHERE ph.tenant_id = 0 AND ph.status = 'COMPLETED' "
                + "   AND pl.work_order_doc_no = x.work_order_doc_no "
                + "   AND related_v.status IN ('APPROVED', 'REVERSED') AND vl.account_code = '1405') "
                + " AS gl_inventory_debit "
                + "FROM (SELECT wo.doc_no AS work_order_doc_no FROM work_order wo WHERE wo.tenant_id = 0 "
                + " UNION SELECT pr.work_order_doc_no FROM production_report pr "
                + " WHERE pr.tenant_id = 0 AND pr.status = 'COMPLETED' "
                + " UNION SELECT pl.work_order_doc_no FROM production_cost_settlement_line pl "
                + " JOIN production_cost_settlement ph ON ph.id = pl.settlement_id "
                + "  AND ph.tenant_id = pl.tenant_id "
                + " WHERE pl.tenant_id = 0 AND ph.status = 'COMPLETED') x "
                + "ORDER BY x.work_order_doc_no", PRODUCTION_INVENTORY_GL_MAPPER);
    }

    /** 将 SQL 聚合中的 NULL 统一收敛为零。 */
    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
