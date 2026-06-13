package com.sjherp.app.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sjherp.app.consistency.ConsistencyCheckDao.BalanceRow;
import com.sjherp.app.consistency.ConsistencyCheckDao.CogsMatchRow;
import com.sjherp.app.consistency.ConsistencyCheckDao.InventoryLedgerRow;
import com.sjherp.app.consistency.ConsistencyCheckDao.PayableMatchRow;
import com.sjherp.app.consistency.ConsistencyCheckDao.PurchaseThreeWayRow;
import com.sjherp.app.consistency.ConsistencyCheckDao.ReceivableMatchRow;
import com.sjherp.app.consistency.ConsistencyCheckDao.SalesThreeWayRow;

/**
 * 一致性校验服务单测（M3-T13）：逐条规则纯比对方法的边界判定。
 *
 * <p>重点回归 BigDecimal#compareTo（非 equals）——标度差异（750 vs 750.00）不误报；
 * 恒等式相等不报、差 0.01 报 ERROR；负余额按 allow-negative 开关分流 ERROR/WARN；
 * 应付/应收/COGS 相符不报、不符报 ERROR；三单量越界报 WARN。
 */
class ConsistencyCheckServiceTest {

    // ===================== 规则1/2：库存恒等式 =====================

    @Test
    void 库存恒等式_数量与金额均相等_不报() {
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkLedger(
                new InventoryLedgerRow(1, 2, new BigDecimal("60"), new BigDecimal("750.00"),
                        new BigDecimal("60"), new BigDecimal("750.00")));
        assertThat(breaks).isEmpty();
    }

    @Test
    void 库存恒等式_标度不同但数值相等_不误报() {
        // Σ流水 750（标度 0）vs 余额 750.00（标度 2）——compareTo=0，不应报
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkLedger(
                new InventoryLedgerRow(1, 2, new BigDecimal("60.000000"), new BigDecimal("750"),
                        new BigDecimal("60"), new BigDecimal("750.00")));
        assertThat(breaks).isEmpty();
    }

    @Test
    void 库存金额恒等式_差0_01_报ERROR() {
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkLedger(
                new InventoryLedgerRow(1, 2, new BigDecimal("60"), new BigDecimal("2000.00"),
                        new BigDecimal("60"), new BigDecimal("1999.99")));
        assertThat(breaks).hasSize(1);
        ConsistencyBreak b = breaks.get(0);
        assertThat(b.checkType()).isEqualTo(ConsistencyCheckType.LEDGER_COST);
        assertThat(b.severity()).isEqualTo(ConsistencySeverity.ERROR);
        assertThat(b.expected()).isEqualTo("2000.00");
        assertThat(b.actual()).isEqualTo("1999.99");
        assertThat(b.key()).isEqualTo("warehouse=1,product=2");
    }

    @Test
    void 库存数量恒等式_数量不平_报ERROR() {
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkLedger(
                new InventoryLedgerRow(1, 2, new BigDecimal("60"), new BigDecimal("750.00"),
                        new BigDecimal("59"), new BigDecimal("750.00")));
        assertThat(breaks).hasSize(1);
        assertThat(breaks.get(0).checkType()).isEqualTo(ConsistencyCheckType.LEDGER_QUANTITY);
        assertThat(breaks.get(0).severity()).isEqualTo(ConsistencySeverity.ERROR);
    }

    // ===================== 规则3：余额非负 =====================

    @Test
    void 负数量_禁负库存_报ERROR() {
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkNegativeBalance(
                new BalanceRow(1, 2, new BigDecimal("-5"), new BigDecimal("10.00")), false);
        assertThat(breaks).hasSize(1);
        assertThat(breaks.get(0).checkType()).isEqualTo(ConsistencyCheckType.NEGATIVE_BALANCE);
        assertThat(breaks.get(0).severity()).isEqualTo(ConsistencySeverity.ERROR);
    }

    @Test
    void 负数量_放行负库存_降级WARN() {
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkNegativeBalance(
                new BalanceRow(1, 2, new BigDecimal("-5"), new BigDecimal("10.00")), true);
        assertThat(breaks).hasSize(1);
        assertThat(breaks.get(0).severity()).isEqualTo(ConsistencySeverity.WARN);
    }

    @Test
    void 负成本也纳入非负校验_数量负与金额负各产一条() {
        // (1, -0.01) 反例：数量负 + 金额负，禁负库存下两条 ERROR
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkNegativeBalance(
                new BalanceRow(1, 2, new BigDecimal("-1"), new BigDecimal("-0.01")), false);
        assertThat(breaks).hasSize(2);
        assertThat(breaks).allMatch(b -> b.severity() == ConsistencySeverity.ERROR);
    }

    @Test
    void 余额非负_不报() {
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkNegativeBalance(
                new BalanceRow(1, 2, new BigDecimal("0"), new BigDecimal("0.00")), false);
        assertThat(breaks).isEmpty();
    }

    // ===================== 规则4：应付 =====================

    @Test
    void 应付额相符_不报() {
        Optional<ConsistencyBreak> b = ConsistencyCheckService.checkPayable(
                new PayableMatchRow("PINV-1", new BigDecimal("800.00"), new BigDecimal("800.00")));
        assertThat(b).isEmpty();
    }

    @Test
    void 应付额不符_报ERROR() {
        Optional<ConsistencyBreak> b = ConsistencyCheckService.checkPayable(
                new PayableMatchRow("PINV-1", new BigDecimal("800.00"), new BigDecimal("750.00")));
        assertThat(b).isPresent();
        assertThat(b.get().checkType()).isEqualTo(ConsistencyCheckType.PAYABLE_AMOUNT);
        assertThat(b.get().severity()).isEqualTo(ConsistencySeverity.ERROR);
        assertThat(b.get().key()).isEqualTo("PINV-1");
    }

    @Test
    void 已过账发票无应付_报ERROR() {
        Optional<ConsistencyBreak> b = ConsistencyCheckService.checkPayable(
                new PayableMatchRow("PINV-1", new BigDecimal("800.00"), null));
        assertThat(b).isPresent();
        assertThat(b.get().severity()).isEqualTo(ConsistencySeverity.ERROR);
        assertThat(b.get().actual()).isNull();
    }

    // ===================== 规则5：应收 =====================

    @Test
    void 应收额相符_不报标度差不误报() {
        Optional<ConsistencyBreak> b = ConsistencyCheckService.checkReceivable(
                new ReceivableMatchRow("SINV-1", new BigDecimal("1750"), new BigDecimal("1750.00")));
        assertThat(b).isEmpty();
    }

    @Test
    void 应收额不符_报ERROR() {
        Optional<ConsistencyBreak> b = ConsistencyCheckService.checkReceivable(
                new ReceivableMatchRow("SINV-1", new BigDecimal("1750.00"), new BigDecimal("1700.00")));
        assertThat(b).isPresent();
        assertThat(b.get().checkType()).isEqualTo(ConsistencyCheckType.RECEIVABLE_AMOUNT);
        assertThat(b.get().severity()).isEqualTo(ConsistencySeverity.ERROR);
    }

    // ===================== 规则6：COGS =====================

    @Test
    void COGS相符_不报() {
        Optional<ConsistencyBreak> b = ConsistencyCheckService.checkCogs(
                new CogsMatchRow("SD-1", 1, new BigDecimal("777.78"), new BigDecimal("777.78")));
        assertThat(b).isEmpty();
    }

    @Test
    void COGS不符_报ERROR() {
        Optional<ConsistencyBreak> b = ConsistencyCheckService.checkCogs(
                new CogsMatchRow("SD-1", 1, new BigDecimal("777.78"), new BigDecimal("777.79")));
        assertThat(b).isPresent();
        assertThat(b.get().checkType()).isEqualTo(ConsistencyCheckType.COGS_MISMATCH);
        assertThat(b.get().severity()).isEqualTo(ConsistencySeverity.ERROR);
        assertThat(b.get().key()).isEqualTo("SD-1#1");
    }

    @Test
    void COGS无对应出库流水_报ERROR() {
        Optional<ConsistencyBreak> b = ConsistencyCheckService.checkCogs(
                new CogsMatchRow("SD-1", 1, new BigDecimal("777.78"), null));
        assertThat(b).isPresent();
        assertThat(b.get().severity()).isEqualTo(ConsistencySeverity.ERROR);
    }

    // ===================== 规则7：三单数量 =====================

    @Test
    void 采购三单_已开票小于等于已收小于等于订单_不报() {
        Optional<ConsistencyBreak> b = ConsistencyCheckService.checkPurchaseThreeWay(
                new PurchaseThreeWayRow("PO-1", 2, new BigDecimal("100"),
                        new BigDecimal("60"), new BigDecimal("60")));
        assertThat(b).isEmpty();
    }

    @Test
    void 采购三单_已收超订单_报WARN() {
        Optional<ConsistencyBreak> b = ConsistencyCheckService.checkPurchaseThreeWay(
                new PurchaseThreeWayRow("PO-1", 2, new BigDecimal("100"),
                        new BigDecimal("110"), new BigDecimal("60")));
        assertThat(b).isPresent();
        assertThat(b.get().checkType()).isEqualTo(ConsistencyCheckType.PURCHASE_THREE_WAY);
        assertThat(b.get().severity()).isEqualTo(ConsistencySeverity.WARN);
    }

    @Test
    void 采购三单_已开票超已收_报WARN() {
        Optional<ConsistencyBreak> b = ConsistencyCheckService.checkPurchaseThreeWay(
                new PurchaseThreeWayRow("PO-1", 2, new BigDecimal("100"),
                        new BigDecimal("60"), new BigDecimal("70")));
        assertThat(b).isPresent();
        assertThat(b.get().severity()).isEqualTo(ConsistencySeverity.WARN);
    }

    @Test
    void 销售三单_已发超订单_报WARN() {
        Optional<ConsistencyBreak> b = ConsistencyCheckService.checkSalesThreeWay(
                new SalesThreeWayRow("SO-1", 2, new BigDecimal("100"),
                        new BigDecimal("110"), new BigDecimal("70")));
        assertThat(b).isPresent();
        assertThat(b.get().checkType()).isEqualTo(ConsistencyCheckType.SALES_THREE_WAY);
        assertThat(b.get().severity()).isEqualTo(ConsistencySeverity.WARN);
    }

    @Test
    void 销售三单_正常_不报() {
        Optional<ConsistencyBreak> b = ConsistencyCheckService.checkSalesThreeWay(
                new SalesThreeWayRow("SO-1", 2, new BigDecimal("100"),
                        new BigDecimal("70"), new BigDecimal("70")));
        assertThat(b).isEmpty();
    }

    // ===================== check() 编排：mock DAO 全平 vs 含 break =====================

    @Test
    void check_全平_报告干净() {
        ConsistencyCheckDao dao = mock(ConsistencyCheckDao.class);
        when(dao.inventoryLedger()).thenReturn(List.of(
                new InventoryLedgerRow(1, 2, new BigDecimal("60"), new BigDecimal("750.00"),
                        new BigDecimal("60"), new BigDecimal("750.00"))));
        when(dao.negativeBalances()).thenReturn(List.of());
        when(dao.payableMatches()).thenReturn(List.of(
                new PayableMatchRow("PINV-1", new BigDecimal("800.00"), new BigDecimal("800.00"))));
        when(dao.receivableMatches()).thenReturn(List.of());
        when(dao.cogsMatches()).thenReturn(List.of());
        when(dao.purchaseThreeWay()).thenReturn(List.of());
        when(dao.salesThreeWay()).thenReturn(List.of());

        ConsistencyCheckService service = new ConsistencyCheckService(dao, false,
                Clock.fixed(Instant.parse("2026-06-13T00:00:00Z"), ZoneOffset.UTC));
        ConsistencyReport report = service.check();

        assertThat(report.clean()).isTrue();
        assertThat(report.checkedAt()).isEqualTo(Instant.parse("2026-06-13T00:00:00Z"));
    }

    @Test
    void check_含多类break_计数正确() {
        ConsistencyCheckDao dao = mock(ConsistencyCheckDao.class);
        when(dao.inventoryLedger()).thenReturn(List.of(
                new InventoryLedgerRow(1, 2, new BigDecimal("60"), new BigDecimal("2000.00"),
                        new BigDecimal("60"), new BigDecimal("1999.99")))); // 1 ERROR
        when(dao.negativeBalances()).thenReturn(List.of(
                new BalanceRow(1, 3, new BigDecimal("-5"), new BigDecimal("10.00")))); // 1 ERROR (禁负库存)
        when(dao.payableMatches()).thenReturn(List.of());
        when(dao.receivableMatches()).thenReturn(List.of());
        when(dao.cogsMatches()).thenReturn(List.of());
        when(dao.purchaseThreeWay()).thenReturn(List.of(
                new PurchaseThreeWayRow("PO-1", 2, new BigDecimal("100"),
                        new BigDecimal("60"), new BigDecimal("70")))); // 1 WARN
        when(dao.salesThreeWay()).thenReturn(List.of());

        ConsistencyCheckService service = new ConsistencyCheckService(dao, false,
                Clock.systemUTC());
        ConsistencyReport report = service.check();

        assertThat(report.clean()).isFalse();
        assertThat(report.errorCount()).isEqualTo(2);
        assertThat(report.warnCount()).isEqualTo(1);
        assertThat(report.breaks()).hasSize(3);
    }
}
