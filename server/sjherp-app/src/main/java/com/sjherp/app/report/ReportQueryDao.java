package com.sjherp.app.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;

/**
 * 进销存报表只读 DAO（M3-T12，<b>只读</b>，参照 {@code InventoryQueryDao} / {@code ConsistencyCheckDao}）。
 *
 * <p>CLAUDE.md 铁律「写操作只能经领域服务，报表/校验只读除外」——本类只有 SELECT/聚合，
 * 零 INSERT/UPDATE/DELETE。金额/数量一律 DECIMAL（不经 float/double），逐行金额在 DTO 层用
 * BigDecimal 现算，跨行合计用 DECIMAL 精确 SQL 聚合（MySQL DECIMAL 运算为精确十进制，非浮点）。
 *
 * <p><b>主表一律 LEFT JOIN</b>：报表是财务/账务产出，绝不能因某维度档案缺失（理论上不应发生）
 * 而<b>静默丢行</b>——宁可显示 null 名称也要把这一行暴露出来，让缺口可见（区别于业务查询的 INNER JOIN）。
 * 因 LEFT JOIN 目标均为主键/唯一键，绝无行膨胀。tenant_id 恒 0（ADR-002）。
 *
 * <p>时间口径：库存流水 created_at 为 UTC DATETIME(6)。期间参数 fromDate/toDate 为业务日，
 * 转半开区间 [fromDate 00:00, (toDate+1) 00:00)（UTC 墙钟）。采购明细按业务日 receipt_date 过滤；
 * 销售出库单无业务日字段（V17 仅有 created_at），故销售明细按过账时间 created_at 过滤（已登记口径差异）。
 */
@Repository
public class ReportQueryDao {

    /** 每页条数上限（防一次拉全表，口径同各档案/库存查询）。 */
    public static final int MAX_SIZE = 200;

    // =====================================================================
    // 1. 库存余额表
    // =====================================================================

    /** 库存余额行（真源两列 quantity/costAmount；派生加权单价在 DTO 层现算）。 */
    public record InventoryBalanceRow(long warehouseId, String warehouseCode, String warehouseName,
                                      long productId, String productCode, String productName,
                                      BigDecimal quantity, BigDecimal costAmount) {
    }

    /** 库存余额报表：当前页 + 全过滤集库存总值（totalCostAmount，Σcost_amount）。 */
    public record InventoryBalanceReport(PageResult<InventoryBalanceRow> page, BigDecimal totalCostAmount) {
    }

    private static final RowMapper<InventoryBalanceRow> BALANCE_MAPPER = (rs, n) -> new InventoryBalanceRow(
            rs.getLong("warehouse_id"), rs.getString("warehouse_code"), rs.getString("warehouse_name"),
            rs.getLong("product_id"), rs.getString("product_code"), rs.getString("product_name"),
            rs.getBigDecimal("quantity"), rs.getBigDecimal("cost_amount"));

    // =====================================================================
    // 2. 收发存汇总
    // =====================================================================

    /** 收发存行：每个(仓库,商品) 期初/收/发/期末 的数量+金额。tie-out：期初+收−发=期末（恒成立）。 */
    public record StockMovementRow(long warehouseId, String warehouseCode, String warehouseName,
                                   long productId, String productCode, String productName,
                                   BigDecimal openingQuantity, BigDecimal openingAmount,
                                   BigDecimal inQuantity, BigDecimal inAmount,
                                   BigDecimal outQuantity, BigDecimal outAmount,
                                   BigDecimal endingQuantity, BigDecimal endingAmount) {
    }

    /** 收发存合计（仅金额可跨商品相加；数量跨商品/单位不可加，故合计只给金额）。 */
    public record StockMovementSummary(BigDecimal totalOpeningAmount, BigDecimal totalInAmount,
                                       BigDecimal totalOutAmount, BigDecimal totalEndingAmount) {
    }

    /** 收发存报表：当前页 + 全过滤集金额合计。 */
    public record StockMovementReport(PageResult<StockMovementRow> page, StockMovementSummary summary) {
    }

    private static final RowMapper<StockMovementRow> MOVEMENT_MAPPER = (rs, n) -> new StockMovementRow(
            rs.getLong("warehouse_id"), rs.getString("warehouse_code"), rs.getString("warehouse_name"),
            rs.getLong("product_id"), rs.getString("product_code"), rs.getString("product_name"),
            nz(rs.getBigDecimal("opening_qty")), nz(rs.getBigDecimal("opening_amt")),
            nz(rs.getBigDecimal("in_qty")), nz(rs.getBigDecimal("in_amt")),
            nz(rs.getBigDecimal("out_qty")), nz(rs.getBigDecimal("out_amt")),
            nz(rs.getBigDecimal("ending_qty")), nz(rs.getBigDecimal("ending_amt")));

    // =====================================================================
    // 3. 采购明细（入库行粒度：实际进货 = 命中库存的口径）
    // =====================================================================

    /** 采购入库明细行（receiptDate 业务日；unitCost=收货单价；amount=数量×单价）。 */
    public record PurchaseDetailRow(String receiptNo, LocalDate receiptDate, String purchaseOrderNo,
                                    Long supplierId, String supplierCode, String supplierName,
                                    long warehouseId, String warehouseCode, String warehouseName,
                                    int lineNo, long productId, String productCode, String productName,
                                    BigDecimal quantity, BigDecimal unitCost, BigDecimal amount,
                                    String status) {
    }

    /** 采购明细报表：当前页 + 全过滤集总进货额（Σamount）。 */
    public record PurchaseDetailReport(PageResult<PurchaseDetailRow> page, BigDecimal totalAmount) {
    }

    private static final RowMapper<PurchaseDetailRow> PURCHASE_MAPPER = (rs, n) -> new PurchaseDetailRow(
            rs.getString("receipt_no"), rs.getObject("receipt_date", LocalDate.class),
            rs.getString("purchase_order_no"),
            longOrNull(rs, "supplier_id"), rs.getString("supplier_code"), rs.getString("supplier_name"),
            rs.getLong("warehouse_id"), rs.getString("warehouse_code"), rs.getString("warehouse_name"),
            rs.getInt("line_no"), rs.getLong("product_id"),
            rs.getString("product_code"), rs.getString("product_name"),
            rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_cost"), rs.getBigDecimal("amount"),
            rs.getString("status"));

    // =====================================================================
    // 4. 销售明细（出库行粒度，联订单行取售价：销售额/COGS/毛利）
    // =====================================================================

    /**
     * 销售出库明细行（unitPrice 取订单行售价，订单行缺失则 null；cogsAmount 未过账为 null；
     * salesAmount/grossProfit 在 DTO 层用 BigDecimal 现算）。
     */
    public record SalesDetailRow(String deliveryNo, LocalDate deliveryDate, String salesOrderNo,
                                 Long customerId, String customerCode, String customerName,
                                 long warehouseId, String warehouseCode, String warehouseName,
                                 int lineNo, long productId, String productCode, String productName,
                                 BigDecimal quantity, BigDecimal unitPrice, BigDecimal cogsAmount,
                                 String status) {
    }

    /** 销售明细合计（销售额 / 成本 / 毛利；毛利=销售额−成本，未过账行无 COGS 时影响毛利口径，建议过滤 status=COMPLETED）。 */
    public record SalesDetailSummary(BigDecimal totalSalesAmount, BigDecimal totalCogsAmount,
                                     BigDecimal totalGrossProfit) {
    }

    /** 销售明细报表：当前页 + 全过滤集合计。 */
    public record SalesDetailReport(PageResult<SalesDetailRow> page, SalesDetailSummary summary) {
    }

    private static final RowMapper<SalesDetailRow> SALES_MAPPER = (rs, n) -> new SalesDetailRow(
            rs.getString("delivery_no"), rs.getObject("delivery_date", LocalDate.class),
            rs.getString("sales_order_no"),
            longOrNull(rs, "customer_id"), rs.getString("customer_code"), rs.getString("customer_name"),
            rs.getLong("warehouse_id"), rs.getString("warehouse_code"), rs.getString("warehouse_name"),
            rs.getInt("line_no"), rs.getLong("product_id"),
            rs.getString("product_code"), rs.getString("product_name"),
            rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("cogs_amount"),
            rs.getString("status"));

    private final JdbcTemplate jdbc;

    public ReportQueryDao(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不能为空");
    }

    // ---------------------------------------------------------------
    // 1. 库存余额表
    // ---------------------------------------------------------------

    /**
     * 库存余额报表：warehouseId / productId / keyword（模糊匹配商品名称或编码）均可选；
     * includeZero=false 时隐藏数量与金额双零行（出空清零后的历史行）。
     */
    @Transactional(readOnly = true)
    public InventoryBalanceReport inventoryBalance(Long warehouseId, Long productId, String keyword,
                                                   boolean includeZero, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);

        StringBuilder where = new StringBuilder(" WHERE b.tenant_id = 0");
        List<Object> args = new ArrayList<>();
        if (warehouseId != null) {
            where.append(" AND b.warehouse_id = ?");
            args.add(warehouseId);
        }
        if (productId != null) {
            where.append(" AND b.product_id = ?");
            args.add(productId);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (p.name LIKE ? OR p.code LIKE ?)");
            String like = "%" + keyword.strip() + "%";
            args.add(like);
            args.add(like);
        }
        if (!includeZero) {
            where.append(" AND (b.quantity <> 0 OR b.cost_amount <> 0)");
        }

        String from = "FROM inventory_balance b"
                + " LEFT JOIN warehouse w ON w.id = b.warehouse_id"
                + " LEFT JOIN product p ON p.id = b.product_id" + where;

        Object[] filterArgs = args.toArray();
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + from, Long.class, filterArgs);
        BigDecimal totalCost = jdbc.queryForObject(
                "SELECT COALESCE(SUM(b.cost_amount), 0) " + from, BigDecimal.class, filterArgs);

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) (safePage - 1) * safeSize);
        List<InventoryBalanceRow> rows = jdbc.query(
                "SELECT b.warehouse_id, w.code AS warehouse_code, w.name AS warehouse_name,"
                        + " b.product_id, p.code AS product_code, p.name AS product_name,"
                        + " b.quantity, b.cost_amount " + from
                        + " ORDER BY b.warehouse_id, b.product_id LIMIT ? OFFSET ?",
                BALANCE_MAPPER, pageArgs.toArray());

        return new InventoryBalanceReport(
                new PageResult<>(rows, total == null ? 0 : total, safePage, safeSize),
                totalCost == null ? BigDecimal.ZERO : totalCost);
    }

    // ---------------------------------------------------------------
    // 2. 收发存汇总
    // ---------------------------------------------------------------

    /**
     * 收发存汇总：[fromDate, toDate] 业务日闭区间（内部转半开 [from 00:00, (to+1) 00:00) UTC）。
     * 维度集 = 期末前（created_at < toExclusive）有任意流水的 (仓库,商品)；warehouseId/productId/keyword 可选。
     * 期初=Σ(created_at&lt;from)；收=Σ期内正向；发=Σ期内负向取正；期末=Σ(created_at&lt;toExclusive)。
     */
    @Transactional(readOnly = true)
    public StockMovementReport stockMovementSummary(LocalDate fromDate, LocalDate toDate,
                                                    Long warehouseId, Long productId, String keyword,
                                                    int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        LocalDateTime fromTs = fromDate.atStartOfDay();
        LocalDateTime toExclusive = toDate.plusDays(1).atStartOfDay();

        // 内层：仅按流水表分组聚合（期末前所有流水），CASE 切分期初/期内收发
        StringBuilder innerWhere = new StringBuilder(" WHERE tenant_id = 0 AND created_at < ?");
        List<Object> innerArgs = new ArrayList<>();
        innerArgs.add(toExclusive);
        if (warehouseId != null) {
            innerWhere.append(" AND warehouse_id = ?");
            innerArgs.add(warehouseId);
        }
        if (productId != null) {
            innerWhere.append(" AND product_id = ?");
            innerArgs.add(productId);
        }
        // 期初的两个 fromTs 参数置于聚合 SELECT 内，须排在 WHERE 参数之前 → 用单独有序列表组装
        String innerSelect = "SELECT warehouse_id, product_id,"
                + " SUM(CASE WHEN created_at < ? THEN quantity ELSE 0 END) AS opening_qty,"
                + " SUM(CASE WHEN created_at < ? THEN total_cost ELSE 0 END) AS opening_amt,"
                + " SUM(CASE WHEN created_at >= ? AND quantity > 0 THEN quantity ELSE 0 END) AS in_qty,"
                + " SUM(CASE WHEN created_at >= ? AND total_cost > 0 THEN total_cost ELSE 0 END) AS in_amt,"
                + " SUM(CASE WHEN created_at >= ? AND quantity < 0 THEN -quantity ELSE 0 END) AS out_qty,"
                + " SUM(CASE WHEN created_at >= ? AND total_cost < 0 THEN -total_cost ELSE 0 END) AS out_amt,"
                + " SUM(quantity) AS ending_qty, SUM(total_cost) AS ending_amt"
                + " FROM inventory_transaction" + innerWhere
                + " GROUP BY warehouse_id, product_id";
        // 聚合 SELECT 内的 6 个时间参数：opening(fromTs×2) + in/out 期内起点(fromTs×4)
        List<Object> selectArgs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            selectArgs.add(fromTs);
        }
        selectArgs.addAll(innerArgs);

        // 外层：联主表取名 + keyword 过滤
        StringBuilder outerWhere = new StringBuilder();
        List<Object> keywordArgs = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            outerWhere.append(" WHERE (p.name LIKE ? OR p.code LIKE ?)");
            String like = "%" + keyword.strip() + "%";
            keywordArgs.add(like);
            keywordArgs.add(like);
        }
        String outerFrom = " FROM (" + innerSelect + ") t"
                + " LEFT JOIN warehouse w ON w.id = t.warehouse_id"
                + " LEFT JOIN product p ON p.id = t.product_id" + outerWhere;

        List<Object> filterArgs = new ArrayList<>(selectArgs);
        filterArgs.addAll(keywordArgs);

        Long total = jdbc.queryForObject("SELECT COUNT(*)" + outerFrom, Long.class, filterArgs.toArray());

        StockMovementSummary summary = jdbc.queryForObject(
                "SELECT COALESCE(SUM(t.opening_amt), 0) AS s_open,"
                        + " COALESCE(SUM(t.in_amt), 0) AS s_in,"
                        + " COALESCE(SUM(t.out_amt), 0) AS s_out,"
                        + " COALESCE(SUM(t.ending_amt), 0) AS s_end" + outerFrom,
                (rs, n) -> new StockMovementSummary(rs.getBigDecimal("s_open"), rs.getBigDecimal("s_in"),
                        rs.getBigDecimal("s_out"), rs.getBigDecimal("s_end")),
                filterArgs.toArray());

        List<Object> pageArgs = new ArrayList<>(filterArgs);
        pageArgs.add(safeSize);
        pageArgs.add((long) (safePage - 1) * safeSize);
        List<StockMovementRow> rows = jdbc.query(
                "SELECT t.warehouse_id, w.code AS warehouse_code, w.name AS warehouse_name,"
                        + " t.product_id, p.code AS product_code, p.name AS product_name,"
                        + " t.opening_qty, t.opening_amt, t.in_qty, t.in_amt,"
                        + " t.out_qty, t.out_amt, t.ending_qty, t.ending_amt" + outerFrom
                        + " ORDER BY t.warehouse_id, t.product_id LIMIT ? OFFSET ?",
                MOVEMENT_MAPPER, pageArgs.toArray());

        return new StockMovementReport(
                new PageResult<>(rows, total == null ? 0 : total, safePage, safeSize),
                summary == null ? new StockMovementSummary(BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO) : summary);
    }

    // ---------------------------------------------------------------
    // 3. 采购明细（入库行粒度）
    // ---------------------------------------------------------------

    /**
     * 采购入库明细：fromDate/toDate（按 receipt_date 业务日，闭区间）、supplierId、productId、
     * warehouseId、status 均可选；按收货日倒序、同日按单 id 倒序、行号正序。
     */
    @Transactional(readOnly = true)
    public PurchaseDetailReport purchaseDetail(LocalDate fromDate, LocalDate toDate, Long supplierId,
                                               Long productId, Long warehouseId, String status,
                                               int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);

        StringBuilder where = new StringBuilder(" WHERE prl.tenant_id = 0");
        List<Object> args = new ArrayList<>();
        if (fromDate != null) {
            where.append(" AND pr.receipt_date >= ?");
            args.add(fromDate);
        }
        if (toDate != null) {
            where.append(" AND pr.receipt_date <= ?");
            args.add(toDate);
        }
        if (supplierId != null) {
            where.append(" AND po.supplier_id = ?");
            args.add(supplierId);
        }
        if (productId != null) {
            where.append(" AND prl.product_id = ?");
            args.add(productId);
        }
        if (warehouseId != null) {
            where.append(" AND pr.warehouse_id = ?");
            args.add(warehouseId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND pr.status = ?");
            args.add(status.strip().toUpperCase(Locale.ROOT));
        }

        String from = "FROM purchase_receipt_line prl"
                + " JOIN purchase_receipt pr ON pr.id = prl.purchase_receipt_id AND pr.tenant_id = 0"
                + " LEFT JOIN purchase_order po ON po.tenant_id = 0 AND po.doc_no = pr.purchase_order_no"
                + " LEFT JOIN supplier s ON s.id = po.supplier_id"
                + " LEFT JOIN warehouse w ON w.id = pr.warehouse_id"
                + " LEFT JOIN product p ON p.id = prl.product_id" + where;

        Object[] filterArgs = args.toArray();
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + from, Long.class, filterArgs);
        BigDecimal totalAmount = jdbc.queryForObject(
                "SELECT COALESCE(SUM(prl.amount), 0) " + from, BigDecimal.class, filterArgs);

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) (safePage - 1) * safeSize);
        List<PurchaseDetailRow> rows = jdbc.query(
                "SELECT pr.doc_no AS receipt_no, pr.receipt_date, pr.purchase_order_no,"
                        + " po.supplier_id, s.code AS supplier_code, s.name AS supplier_name,"
                        + " pr.warehouse_id, w.code AS warehouse_code, w.name AS warehouse_name,"
                        + " prl.line_no, prl.product_id, p.code AS product_code, p.name AS product_name,"
                        + " prl.quantity, prl.unit_cost, prl.amount, pr.status " + from
                        + " ORDER BY pr.receipt_date DESC, pr.id DESC, prl.line_no LIMIT ? OFFSET ?",
                PURCHASE_MAPPER, pageArgs.toArray());

        return new PurchaseDetailReport(
                new PageResult<>(rows, total == null ? 0 : total, safePage, safeSize),
                totalAmount == null ? BigDecimal.ZERO : totalAmount);
    }

    // ---------------------------------------------------------------
    // 4. 销售明细（出库行粒度）
    // ---------------------------------------------------------------

    /**
     * 销售出库明细：fromDate/toDate（按出库单 created_at 过账时间，半开区间）、customerId、productId、
     * warehouseId、status 均可选；销售额/毛利合计用 DECIMAL 精确 SQL 聚合（逐行金额在 DTO 层现算）。
     */
    @Transactional(readOnly = true)
    public SalesDetailReport salesDetail(LocalDate fromDate, LocalDate toDate, Long customerId,
                                         Long productId, Long warehouseId, String status,
                                         int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);

        StringBuilder where = new StringBuilder(" WHERE sdl.tenant_id = 0");
        List<Object> args = new ArrayList<>();
        if (fromDate != null) {
            where.append(" AND sd.created_at >= ?");
            args.add(fromDate.atStartOfDay());
        }
        if (toDate != null) {
            where.append(" AND sd.created_at < ?");
            args.add(toDate.plusDays(1).atStartOfDay());
        }
        if (customerId != null) {
            where.append(" AND so.customer_id = ?");
            args.add(customerId);
        }
        if (productId != null) {
            where.append(" AND sdl.product_id = ?");
            args.add(productId);
        }
        if (warehouseId != null) {
            where.append(" AND sd.warehouse_id = ?");
            args.add(warehouseId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND sd.status = ?");
            args.add(status.strip().toUpperCase(Locale.ROOT));
        }

        String from = "FROM sales_delivery_line sdl"
                + " JOIN sales_delivery sd ON sd.id = sdl.sales_delivery_id AND sd.tenant_id = 0"
                + " LEFT JOIN sales_order so ON so.tenant_id = 0 AND so.doc_no = sd.sales_order_no"
                + " LEFT JOIN sales_order_line sol ON sol.tenant_id = 0 AND sol.sales_order_id = so.id"
                + "   AND sol.line_no = sdl.so_line_no"
                + " LEFT JOIN customer c ON c.id = so.customer_id"
                + " LEFT JOIN warehouse w ON w.id = sd.warehouse_id"
                + " LEFT JOIN product p ON p.id = sdl.product_id" + where;

        Object[] filterArgs = args.toArray();
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + from, Long.class, filterArgs);

        // 销售额/成本/毛利合计：DECIMAL 精确聚合（逐行售额 = ROUND(数量×售价,2)，与 DTO 层逐行口径一致）
        SalesDetailSummary summary = jdbc.queryForObject(
                "SELECT COALESCE(SUM(ROUND(sdl.quantity * sol.unit_price, 2)), 0) AS s_sales,"
                        + " COALESCE(SUM(sdl.cogs_amount), 0) AS s_cogs,"
                        + " COALESCE(SUM(ROUND(sdl.quantity * sol.unit_price, 2)), 0)"
                        + "   - COALESCE(SUM(sdl.cogs_amount), 0) AS s_gp " + from,
                (rs, n) -> new SalesDetailSummary(rs.getBigDecimal("s_sales"), rs.getBigDecimal("s_cogs"),
                        rs.getBigDecimal("s_gp")),
                filterArgs);

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) (safePage - 1) * safeSize);
        List<SalesDetailRow> rows = jdbc.query(
                "SELECT sd.doc_no AS delivery_no, DATE(sd.created_at) AS delivery_date, sd.sales_order_no,"
                        + " so.customer_id, c.code AS customer_code, c.name AS customer_name,"
                        + " sd.warehouse_id, w.code AS warehouse_code, w.name AS warehouse_name,"
                        + " sdl.line_no, sdl.product_id, p.code AS product_code, p.name AS product_name,"
                        + " sdl.quantity, sol.unit_price, sdl.cogs_amount, sd.status " + from
                        + " ORDER BY sd.created_at DESC, sd.id DESC, sdl.line_no LIMIT ? OFFSET ?",
                SALES_MAPPER, pageArgs.toArray());

        return new SalesDetailReport(
                new PageResult<>(rows, total == null ? 0 : total, safePage, safeSize),
                summary == null ? new SalesDetailSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
                        : summary);
    }

    /** SUM/聚合列可能为 NULL（空集），统一收敛为 0。 */
    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 可空 BIGINT 列读取（LEFT JOIN 缺失侧返回 null，而非 0）。 */
    private static Long longOrNull(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}
