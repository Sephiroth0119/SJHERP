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
import com.sjherp.app.consistency.ConsistencyCheckDao.SettlementRollupRow;
import com.sjherp.app.consistency.ConsistencyCheckDao.WorkOrderCostSettledRow;

/**
 * 数据一致性校验服务（M3-T13 检查 Agent 核心引擎，<b>只读</b>）。
 *
 * <p>跑全部勾稽规则（七条进销存/财务勾稽 + M4-T04c 三条核销 rollup 勾稽，docs 业务文档
 * 「数据一致性校验」§2），逐条把对不上的差异收集为
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
     * 跑全部勾稽校验（库存/财务七条 + 核销 rollup 三条），产出结构化报告。只读，不改账。
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
        // 规则8/9/10（M4-T04c）：核销 rollup / 无超额 / 状态-余额 一致（应收 + 应付）
        for (SettlementRollupRow row : dao.receivableRollups()) {
            breaks.addAll(checkSettlementRollup(row));
        }
        for (SettlementRollupRow row : dao.payableRollups()) {
            breaks.addAll(checkSettlementRollup(row));
        }
        // 规则11（M5-T06，D9）：已完工工单工费已结转（WARN 非阻塞）
        for (WorkOrderCostSettledRow row : dao.workOrderCostSettled()) {
            checkWorkOrderCostSettled(row).ifPresent(breaks::add);
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

    /**
     * 规则8/9/10（M4-T04c）：对一笔应收/应付的核销三连校验（任一不符各产一条 break）。
     *
     * <ul>
     *   <li><b>规则8 rollup 一致</b>（{@link ConsistencyCheckType#SETTLEMENT_ROLLUP}，ERROR）：
     *       子账 {@code settled_amount} 必须等于核销记录 Σamount（核销真源）。子账 rollup 与真源对不上 = 账实不一致；</li>
     *   <li><b>规则9 无超额</b>（{@link ConsistencyCheckType#SETTLEMENT_OVER}，ERROR）：
     *       {@code settled_amount > amount} 即越权超额持久化（领域层本已硬拒，此为直插库兜底）；</li>
     *   <li><b>规则10 状态-余额一致</b>（{@link ConsistencyCheckType#SETTLEMENT_STATUS}，ERROR）：
     *       余额 = amount − settled。OPEN⟺settled=0；SETTLED⟺余额=0（且 amount&gt;0）；PARTIAL⟺0&lt;settled&lt;amount。
     *       三态互斥全覆盖，落不进任一态（含状态串值非法）即状态机被旁路。</li>
     * </ul>
     *
     * <p>三条彼此独立，可同时命中（如超额且状态错），各报各的，便于定位。
     * 全程 {@link BigDecimal#compareTo} 比较（规避 750 与 750.00 标度差异）。
     */
    static List<ConsistencyBreak> checkSettlementRollup(SettlementRollupRow row) {
        List<ConsistencyBreak> result = new ArrayList<>(3);
        String key = row.sourceDocNo() + "#" + row.settlementType() + "#" + row.targetId();
        BigDecimal amount = nz(row.amount());
        BigDecimal settled = nz(row.settledAmount());
        BigDecimal recordSum = nz(row.recordSettledSum());

        // 规则8：子账 settled_amount == Σ 核销记录金额（核销真源）
        if (settled.compareTo(recordSum) != 0) {
            result.add(ConsistencyBreak.of(ConsistencyCheckType.SETTLEMENT_ROLLUP, key,
                    recordSum, settled, ConsistencySeverity.ERROR,
                    "核销 rollup 不一致：子账已核销额 ≠ Σ核销记录金额（" + key + "）"));
        }
        // 规则9：settled_amount <= amount（无超额持久化）
        if (settled.compareTo(amount) > 0) {
            result.add(ConsistencyBreak.of(ConsistencyCheckType.SETTLEMENT_OVER, key,
                    amount, settled, ConsistencySeverity.ERROR,
                    "核销额超过应收/应付总额（越权超额持久化）：" + key));
        }
        // 规则10：状态 ⟺ 余额（OPEN/PARTIAL/SETTLED/REVERSED 四态互斥全覆盖）
        BigDecimal open = amount.subtract(settled);
        String status = row.status();
        boolean statusOk;
        if ("OPEN".equals(status)) {
            statusOk = settled.signum() == 0;
        } else if ("SETTLED".equals(status)) {
            // 已核销：余额为 0 且确有金额（amount=0 的空单不应标 SETTLED）
            statusOk = open.signum() == 0 && amount.signum() > 0;
        } else if ("PARTIAL".equals(status)) {
            statusOk = settled.signum() > 0 && open.signum() > 0;
        } else if ("REVERSED".equals(status)) {
            // 已冲销（M4-T07b 业务发票红冲）：仅未核销发票可冲销（canBeReversed 要求 settled==0），
            // 故 REVERSED 子账必须 settled==0；仍校验以揪出"已核销却被直插改 REVERSED"的腐败。
            // 红冲子账无未核销余额义务，不参与 rollup 错报（红冲后月末关账闸门不被其阻塞）。
            statusOk = settled.signum() == 0;
        } else {
            statusOk = false; // 状态串值非法（非四态之一）
        }
        if (!statusOk) {
            result.add(ConsistencyBreak.of(ConsistencyCheckType.SETTLEMENT_STATUS, key,
                    open, settled, ConsistencySeverity.ERROR,
                    "核销状态与余额不一致：status=" + status + "，总额=" + amount.toPlainString()
                            + "，已核销=" + settled.toPlainString() + "，余额=" + open.toPlainString()
                            + "（" + key + "）"));
        }
        return result;
    }

    /**
     * 规则11（M5-T06，D9，WARN）：已完工工单（completed_qty&gt;0）应有已过账成本结转行，
     * 缺失则报 WARN（完工工费尚未月末结转，提醒非阻塞，避免误卡关账）。
     */
    static java.util.Optional<ConsistencyBreak> checkWorkOrderCostSettled(WorkOrderCostSettledRow row) {
        if (row.settlementLineCount() == 0) {
            return java.util.Optional.of(ConsistencyBreak.of(
                    ConsistencyCheckType.WORK_ORDER_COST_UNSETTLED, row.workOrderDocNo(),
                    row.completedQty(), BigDecimal.ZERO, ConsistencySeverity.WARN,
                    "已完工工单工费尚未月末结转（完工量=" + nz(row.completedQty()).toPlainString()
                            + "，无已过账成本结转记录）：" + row.workOrderDocNo()));
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
