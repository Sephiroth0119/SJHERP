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
                + "LEFT JOIN accounts_payable ap ON ap.tenant_id = 0 AND ap.source_doc_no = pi.doc_no "
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
                + "LEFT JOIN accounts_receivable ar ON ar.tenant_id = 0 AND ar.source_doc_no = si.doc_no "
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

    /** SUM/列可能为 NULL（无对应行），统一收敛为 0。 */
    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
