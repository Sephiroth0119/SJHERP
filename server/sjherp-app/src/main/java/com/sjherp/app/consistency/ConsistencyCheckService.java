package com.sjherp.app.consistency;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.app.consistency.ConsistencyCheckDao.BalanceRow;
import com.sjherp.app.consistency.ConsistencyCheckDao.CogsMatchRow;
import com.sjherp.app.consistency.ConsistencyCheckDao.InventoryLedgerRow;
import com.sjherp.app.consistency.ConsistencyCheckDao.PayableMatchRow;
import com.sjherp.app.consistency.ConsistencyCheckDao.PurchaseThreeWayRow;
import com.sjherp.app.consistency.ConsistencyCheckDao.ReceivableMatchRow;
import com.sjherp.app.consistency.ConsistencyCheckDao.SalesThreeWayRow;

/**
 * 数据一致性校验服务（M3-T13 检查 Agent 核心引擎，<b>只读</b>）。
 *
 * <p>跑七条勾稽规则（docs 业务文档「数据一致性校验」§2），逐条把对不上的差异收集为
 * {@link ConsistencyBreak} 汇总成 {@link ConsistencyReport}。读路径全部经
 * {@link ConsistencyCheckDao} 只读聚合 SQL（CLAUDE.md 铁律「报表/校验只读除外」），
 * <b>本类零写路径</b>——纠错仍走正常业务单据（红字冲销），绝不静默改账。
 *
 * <p>关键技术决策：
 * <ul>
 *   <li>全部金额/数量用 {@link BigDecimal#compareTo}（不用 equals，规避标度差异，
 *       如 750 与 750.00 视为相等）；</li>
 *   <li>负库存配置 {@code sjherp.inventory.allow-negative-stock}（默认 false）注入：
 *       规则3 余额非负在开关为 false 时报 {@link ConsistencySeverity#ERROR}（账被击穿），
 *       开关为 true 时降级 {@link ConsistencySeverity#WARN}（已知放行态，docs §1.5）；</li>
 *   <li>严重度：恒等式破坏/应付应收/COGS 不符为 ERROR（动了真账）；三单数量越界为 WARN。</li>
 * </ul>
 *
 * <p>比对逻辑抽成静态纯方法（入参为聚合行 record），便于单测覆盖边界；SQL 由集成测试覆盖。
 */
@Service
public class ConsistencyCheckService {

    private final ConsistencyCheckDao dao;
    private final boolean allowNegativeStock;
    private final Clock clock;

    public ConsistencyCheckService(ConsistencyCheckDao dao,
                                   @Value("${sjherp.inventory.allow-negative-stock:false}")
                                   boolean allowNegativeStock) {
        this(dao, allowNegativeStock, Clock.systemUTC());
    }

    /** 测试构造（注入固定 Clock）。 */
    ConsistencyCheckService(ConsistencyCheckDao dao, boolean allowNegativeStock, Clock clock) {
        this.dao = Objects.requireNonNull(dao, "dao 不能为空");
        this.allowNegativeStock = allowNegativeStock;
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 跑全部七条勾稽校验，产出结构化报告。只读，不改账。
     */
    @Transactional(readOnly = true)
    public ConsistencyReport check() {
        List<ConsistencyBreak> breaks = new ArrayList<>();

        // 规则1/2：库存流水Σ = 余额（数量、金额各一条恒等式）
        for (InventoryLedgerRow row : dao.inventoryLedger()) {
            breaks.addAll(checkLedger(row));
        }
        // 规则3：库存余额非负
        for (BalanceRow row : dao.negativeBalances()) {
            breaks.addAll(checkNegativeBalance(row, allowNegativeStock));
        }
        // 规则4：应付 = 已过账采购发票额
        for (PayableMatchRow row : dao.payableMatches()) {
            checkPayable(row).ifPresent(breaks::add);
        }
        // 规则5：应收 = 已过账销售发票额
        for (ReceivableMatchRow row : dao.receivableMatches()) {
            checkReceivable(row).ifPresent(breaks::add);
        }
        // 规则6：出库 COGS = SALES_OUT Σ|total_cost|
        for (CogsMatchRow row : dao.cogsMatches()) {
            checkCogs(row).ifPresent(breaks::add);
        }
        // 规则7：采购三单数量勾稽
        for (PurchaseThreeWayRow row : dao.purchaseThreeWay()) {
            checkPurchaseThreeWay(row).ifPresent(breaks::add);
        }
        // 规则7：销售三单数量勾稽
        for (SalesThreeWayRow row : dao.salesThreeWay()) {
            checkSalesThreeWay(row).ifPresent(breaks::add);
        }

        return new ConsistencyReport(clock.instant(), breaks);
    }

    // ===============================================================
    // 纯比对方法（无 IO，可单测）——入参为 DAO 聚合行
    // ===============================================================

    /** 规则1/2：数量恒等式 + 金额恒等式（各自不平各产一条 break）。 */
    static List<ConsistencyBreak> checkLedger(InventoryLedgerRow row) {
        List<ConsistencyBreak> result = new ArrayList<>(2);
        String key = inventoryKey(row.warehouseId(), row.productId());
        if (row.txnQuantitySum().compareTo(row.balanceQuantity()) != 0) {
            result.add(ConsistencyBreak.of(ConsistencyCheckType.LEDGER_QUANTITY, key,
                    row.txnQuantitySum(), row.balanceQuantity(), ConsistencySeverity.ERROR,
                    "库存数量恒等式破坏：Σ流水数量 ≠ 余额数量（" + key + "）"));
        }
        if (row.txnCostSum().compareTo(row.balanceCostAmount()) != 0) {
            result.add(ConsistencyBreak.of(ConsistencyCheckType.LEDGER_COST, key,
                    row.txnCostSum(), row.balanceCostAmount(), ConsistencySeverity.ERROR,
                    "库存金额恒等式破坏：Σ流水金额 ≠ 余额结存金额（" + key + "）"));
        }
        return result;
    }

    /**
     * 规则3：余额非负（数量、金额任一 &lt; 0 各产一条 break）。
     * 禁负库存（allowNegative=false）时为 ERROR（账被击穿）；放行时降级 WARN（已知态）。
     */
    static List<ConsistencyBreak> checkNegativeBalance(BalanceRow row, boolean allowNegative) {
        List<ConsistencyBreak> result = new ArrayList<>(2);
        String key = inventoryKey(row.warehouseId(), row.productId());
        ConsistencySeverity severity = allowNegative ? ConsistencySeverity.WARN : ConsistencySeverity.ERROR;
        if (row.quantity() != null && row.quantity().signum() < 0) {
            result.add(ConsistencyBreak.of(ConsistencyCheckType.NEGATIVE_BALANCE, key,
                    BigDecimal.ZERO, row.quantity(), severity,
                    "库存数量为负（" + key + "）"
                            + (allowNegative ? "：负库存已配置放行" : "：禁负库存下出现负余额，库存被击穿")));
        }
        if (row.costAmount() != null && row.costAmount().signum() < 0) {
            result.add(ConsistencyBreak.of(ConsistencyCheckType.NEGATIVE_BALANCE, key,
                    BigDecimal.ZERO, row.costAmount(), severity,
                    "库存金额为负（" + key + "）"
                            + (allowNegative ? "：负库存已配置放行" : "：禁负库存下出现负成本，库存被击穿")));
        }
        return result;
    }

    /** 规则4：应付额 = 已过账采购发票额（无应付行或不符均报 ERROR）。 */
    static java.util.Optional<ConsistencyBreak> checkPayable(PayableMatchRow row) {
        BigDecimal expected = nz(row.invoiceAmount());
        BigDecimal actual = row.payableAmount();
        if (actual == null) {
            return java.util.Optional.of(ConsistencyBreak.of(ConsistencyCheckType.PAYABLE_AMOUNT,
                    row.invoiceNo(), expected, null, ConsistencySeverity.ERROR,
                    "已过账采购发票未生成对应应付：" + row.invoiceNo()));
        }
        if (expected.compareTo(actual) != 0) {
            return java.util.Optional.of(ConsistencyBreak.of(ConsistencyCheckType.PAYABLE_AMOUNT,
                    row.invoiceNo(), expected, actual, ConsistencySeverity.ERROR,
                    "应付金额与采购发票额不符：" + row.invoiceNo()));
        }
        return java.util.Optional.empty();
    }

    /** 规则5：应收额 = 已过账销售发票额（无应收行或不符均报 ERROR）。 */
    static java.util.Optional<ConsistencyBreak> checkReceivable(ReceivableMatchRow row) {
        BigDecimal expected = nz(row.invoiceAmount());
        BigDecimal actual = row.receivableAmount();
        if (actual == null) {
            return java.util.Optional.of(ConsistencyBreak.of(ConsistencyCheckType.RECEIVABLE_AMOUNT,
                    row.invoiceNo(), expected, null, ConsistencySeverity.ERROR,
                    "已过账销售发票未生成对应应收：" + row.invoiceNo()));
        }
        if (expected.compareTo(actual) != 0) {
            return java.util.Optional.of(ConsistencyBreak.of(ConsistencyCheckType.RECEIVABLE_AMOUNT,
                    row.invoiceNo(), expected, actual, ConsistencySeverity.ERROR,
                    "应收金额与销售发票额不符：" + row.invoiceNo()));
        }
        return java.util.Optional.empty();
    }

    /** 规则6：出库行 COGS = 该出库行 SALES_OUT 流水金额合计（绝对值），不符报 ERROR。 */
    static java.util.Optional<ConsistencyBreak> checkCogs(CogsMatchRow row) {
        String key = row.deliveryNo() + "#" + row.lineNo();
        BigDecimal cogs = nz(row.cogsAmount());
        BigDecimal salesOut = row.salesOutCostSum();
        if (salesOut == null) {
            return java.util.Optional.of(ConsistencyBreak.of(ConsistencyCheckType.COGS_MISMATCH,
                    key, cogs, null, ConsistencySeverity.ERROR,
                    "出库行有 COGS 但无对应 SALES_OUT 出库流水：" + key));
        }
        if (cogs.compareTo(salesOut) != 0) {
            return java.util.Optional.of(ConsistencyBreak.of(ConsistencyCheckType.COGS_MISMATCH,
                    key, cogs, salesOut, ConsistencySeverity.ERROR,
                    "出库行 COGS 与库存出库流水金额不符：" + key));
        }
        return java.util.Optional.empty();
    }

    /** 规则7：采购「已开票量 ≤ 已收量 ≤ 订单量」，任一越界报 WARN。 */
    static java.util.Optional<ConsistencyBreak> checkPurchaseThreeWay(PurchaseThreeWayRow row) {
        String key = row.orderNo() + ",product=" + row.productId();
        if (row.receivedQty().compareTo(row.orderedQty()) > 0) {
            return java.util.Optional.of(ConsistencyBreak.of(ConsistencyCheckType.PURCHASE_THREE_WAY,
                    key, row.orderedQty(), row.receivedQty(), ConsistencySeverity.WARN,
                    "采购已收量超过订单量：" + key));
        }
        if (row.invoicedQty().compareTo(row.receivedQty()) > 0) {
            return java.util.Optional.of(ConsistencyBreak.of(ConsistencyCheckType.PURCHASE_THREE_WAY,
                    key, row.receivedQty(), row.invoicedQty(), ConsistencySeverity.WARN,
                    "采购已开票量超过已收量：" + key));
        }
        return java.util.Optional.empty();
    }

    /** 规则7：销售「已开票量 ≤ 已发量 ≤ 订单量」，任一越界报 WARN。 */
    static java.util.Optional<ConsistencyBreak> checkSalesThreeWay(SalesThreeWayRow row) {
        String key = row.orderNo() + ",product=" + row.productId();
        if (row.deliveredQty().compareTo(row.orderedQty()) > 0) {
            return java.util.Optional.of(ConsistencyBreak.of(ConsistencyCheckType.SALES_THREE_WAY,
                    key, row.orderedQty(), row.deliveredQty(), ConsistencySeverity.WARN,
                    "销售已发量超过订单量：" + key));
        }
        if (row.invoicedQty().compareTo(row.deliveredQty()) > 0) {
            return java.util.Optional.of(ConsistencyBreak.of(ConsistencyCheckType.SALES_THREE_WAY,
                    key, row.deliveredQty(), row.invoicedQty(), ConsistencySeverity.WARN,
                    "销售已开票量超过已发量：" + key));
        }
        return java.util.Optional.empty();
    }

    private static String inventoryKey(long warehouseId, long productId) {
        return "warehouse=" + warehouseId + ",product=" + productId;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
