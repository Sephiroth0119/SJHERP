package com.sjherp.app.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sjherp.app.consistency.ConsistencyCheckDao.SettlementRollupRow;

/**
 * 核销 rollup 勾稽单测（M4-T04c，规则8/9/10）：纯比对方法 {@link ConsistencyCheckService#checkSettlementRollup}
 * 的边界判定 + {@link ConsistencyCheckService#check()} 编排把应收/应付 rollup 行纳入报告。
 *
 * <p>三条规则彼此独立（可同时命中各报各的），全程 {@link BigDecimal#compareTo} 比较（不用 equals，规避标度差异）：
 * <ul>
 *   <li>规则8 {@link ConsistencyCheckType#SETTLEMENT_ROLLUP}：子账 settled_amount == Σ核销记录金额；</li>
 *   <li>规则9 {@link ConsistencyCheckType#SETTLEMENT_OVER}：settled_amount ≤ amount；</li>
 *   <li>规则10 {@link ConsistencyCheckType#SETTLEMENT_STATUS}：OPEN⟺settled=0、PARTIAL⟺0&lt;settled&lt;amount、
 *       SETTLED⟺余额0 且 amount&gt;0（含状态串值非法）。</li>
 * </ul>
 *
 * <p>金额都用 {@code toPlainString()} 承载于 break，故断言 expected/actual 用字符串。
 */
class SettlementRollupConsistencyTest {

    /** 便捷构造：应收 rollup 行（settlementType=RECEIVABLE）。 */
    private static SettlementRollupRow ar(long targetId, String docNo, String amount,
                                          String settled, String status, String recordSum) {
        return new SettlementRollupRow("RECEIVABLE", targetId, docNo,
                new BigDecimal(amount), new BigDecimal(settled), status, new BigDecimal(recordSum));
    }

    /** 便捷构造：应付 rollup 行（settlementType=PAYABLE）。 */
    private static SettlementRollupRow ap(long targetId, String docNo, String amount,
                                          String settled, String status, String recordSum) {
        return new SettlementRollupRow("PAYABLE", targetId, docNo,
                new BigDecimal(amount), new BigDecimal(settled), status, new BigDecimal(recordSum));
    }

    // ===================== 干净基线：三态正常各不报 =====================

    @Test
    void 未核销OPEN_settled0_记录0_全平不报() {
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkSettlementRollup(
                ar(5, "SINV-1", "1500.00", "0.00", "OPEN", "0.00"));
        assertThat(breaks).isEmpty();
    }

    @Test
    void 部分核销PARTIAL_settled介于0与amount_记录相符_不报() {
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkSettlementRollup(
                ar(5, "SINV-1", "1500.00", "1000.00", "PARTIAL", "1000.00"));
        assertThat(breaks).isEmpty();
    }

    @Test
    void 全额核销SETTLED_余额0且amount大于0_记录相符_不报() {
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkSettlementRollup(
                ap(7, "PINV-1", "1250.00", "1250.00", "SETTLED", "1250.00"));
        assertThat(breaks).isEmpty();
    }

    @Test
    void 标度差异_settled750与记录750点00_判一致不误报() {
        // 子账 settled=750（标度 0）vs 记录 Σ=750.00（标度 2）——compareTo=0，规则8 不应报；
        // 同时 amount=1500.00 余额>0 + PARTIAL 状态相符，规则9/10 也不报。
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkSettlementRollup(
                ar(5, "SINV-1", "1500.00", "750", "PARTIAL", "750.00"));
        assertThat(breaks).isEmpty();
    }

    // ===================== 规则8：rollup 不一致（settled ≠ Σ记录） =====================

    @Test
    void rollup不一致_子账settled多于记录_报SETTLEMENT_ROLLUP_ERROR() {
        // 直插把子账 settled +1，但核销记录未同步 → 子账 rollup 与真源脱钩。
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkSettlementRollup(
                ar(5, "SINV-1", "1500.00", "1001.00", "PARTIAL", "1000.00"));
        assertThat(breaks).hasSize(1);
        ConsistencyBreak b = breaks.get(0);
        assertThat(b.checkType()).isEqualTo(ConsistencyCheckType.SETTLEMENT_ROLLUP);
        assertThat(b.severity()).isEqualTo(ConsistencySeverity.ERROR);
        assertThat(b.key()).isEqualTo("SINV-1#RECEIVABLE#5");
        // of(checkType,key,expected=recordSum,actual=settled,...)：期望=核销真源、实际=子账 rollup
        assertThat(b.expected()).isEqualTo("1000.00");
        assertThat(b.actual()).isEqualTo("1001.00");
    }

    @Test
    void rollup不一致_记录多于子账settled_报SETTLEMENT_ROLLUP_ERROR() {
        // 反向脱钩：多插一条核销记录而不动子账 settled。
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkSettlementRollup(
                ap(7, "PINV-1", "1250.00", "1000.00", "PARTIAL", "1100.00"));
        assertThat(breaks).hasSize(1);
        ConsistencyBreak b = breaks.get(0);
        assertThat(b.checkType()).isEqualTo(ConsistencyCheckType.SETTLEMENT_ROLLUP);
        assertThat(b.severity()).isEqualTo(ConsistencySeverity.ERROR);
        assertThat(b.key()).isEqualTo("PINV-1#PAYABLE#7");
    }

    @Test
    void rollup孤儿_子账settled非0但无核销记录_报SETTLEMENT_ROLLUP_ERROR() {
        // 子账 settled 非 0、记录 Σ=0（LEFT JOIN COALESCE 收敛）→ 暴露孤儿（settled 无真源支撑）。
        // 同时状态 PARTIAL 与余额相符（settled=500 0<500<1500），规则10 不命中，便于隔离断言规则8。
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkSettlementRollup(
                ar(5, "SINV-1", "1500.00", "500.00", "PARTIAL", "0.00"));
        assertThat(breaks).hasSize(1);
        assertThat(breaks.get(0).checkType()).isEqualTo(ConsistencyCheckType.SETTLEMENT_ROLLUP);
        assertThat(breaks.get(0).severity()).isEqualTo(ConsistencySeverity.ERROR);
    }

    // ===================== 规则9：超额（settled > amount） =====================

    @Test
    void 超额_settled大于amount_报SETTLEMENT_OVER_ERROR() {
        // 越权直插 settled = amount + 0.01，并把记录补到同额（让规则8 通过、隔离出仅规则9 命中）。
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkSettlementRollup(
                ap(7, "PINV-1", "1250.00", "1250.01", "SETTLED", "1250.01"));
        // settled>amount 命中规则9；status=SETTLED 但余额=-0.01≠0 → 规则10 也命中（两条独立，均预期）
        assertThat(breaks).extracting(ConsistencyBreak::checkType)
                .contains(ConsistencyCheckType.SETTLEMENT_OVER);
        ConsistencyBreak over = breaks.stream()
                .filter(b -> b.checkType() == ConsistencyCheckType.SETTLEMENT_OVER)
                .findFirst().orElseThrow();
        assertThat(over.severity()).isEqualTo(ConsistencySeverity.ERROR);
        assertThat(over.key()).isEqualTo("PINV-1#PAYABLE#7");
        // of(...,expected=amount,actual=settled,...)
        assertThat(over.expected()).isEqualTo("1250.00");
        assertThat(over.actual()).isEqualTo("1250.01");
    }

    @Test
    void settled等于amount_边界不报超额() {
        // 边界：settled == amount（恰好全额）不算超额（compareTo>0 才报）。
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkSettlementRollup(
                ar(5, "SINV-1", "1500.00", "1500.00", "SETTLED", "1500.00"));
        assertThat(breaks).isEmpty();
    }

    // ===================== 规则10：状态-余额不一致 =====================

    @Test
    void 状态SETTLED但余额大于0_报SETTLEMENT_STATUS_ERROR() {
        // 余额>0（settled<amount）却标 SETTLED → 状态机被旁路。记录与 settled 相符、未超额，仅命中规则10。
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkSettlementRollup(
                ar(5, "SINV-1", "1500.00", "1000.00", "SETTLED", "1000.00"));
        assertThat(breaks).hasSize(1);
        ConsistencyBreak b = breaks.get(0);
        assertThat(b.checkType()).isEqualTo(ConsistencyCheckType.SETTLEMENT_STATUS);
        assertThat(b.severity()).isEqualTo(ConsistencySeverity.ERROR);
        assertThat(b.key()).isEqualTo("SINV-1#RECEIVABLE#5");
    }

    @Test
    void 状态OPEN但已核销大于0_报SETTLEMENT_STATUS_ERROR() {
        // settled>0 却标 OPEN（OPEN⟺settled=0）。记录与 settled 相符、未超额，仅命中规则10。
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkSettlementRollup(
                ap(7, "PINV-1", "1250.00", "500.00", "OPEN", "500.00"));
        assertThat(breaks).hasSize(1);
        assertThat(breaks.get(0).checkType()).isEqualTo(ConsistencyCheckType.SETTLEMENT_STATUS);
        assertThat(breaks.get(0).severity()).isEqualTo(ConsistencySeverity.ERROR);
    }

    @Test
    void 状态PARTIAL但已全额_报SETTLEMENT_STATUS_ERROR() {
        // 余额=0（已全额）却标 PARTIAL（PARTIAL⟺0<settled<amount，余额须>0）。
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkSettlementRollup(
                ar(5, "SINV-1", "1500.00", "1500.00", "PARTIAL", "1500.00"));
        assertThat(breaks).hasSize(1);
        assertThat(breaks.get(0).checkType()).isEqualTo(ConsistencyCheckType.SETTLEMENT_STATUS);
    }

    @Test
    void 状态串值非法_报SETTLEMENT_STATUS_ERROR() {
        // 非三态之一（如直插脏 status）→ 落不进任一态，规则10 命中。
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkSettlementRollup(
                ar(5, "SINV-1", "1500.00", "0.00", "FOO", "0.00"));
        assertThat(breaks).hasSize(1);
        assertThat(breaks.get(0).checkType()).isEqualTo(ConsistencyCheckType.SETTLEMENT_STATUS);
        assertThat(breaks.get(0).severity()).isEqualTo(ConsistencySeverity.ERROR);
    }

    @Test
    void amount0的空单标SETTLED_报SETTLEMENT_STATUS_ERROR() {
        // amount=0 且 settled=0：余额=0 但 amount 非>0 → 不应标 SETTLED（SETTLED 须 amount>0）。
        // 这类空单只能合法停在 OPEN（settled=0），标 SETTLED 即状态非法。
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkSettlementRollup(
                ar(5, "SINV-0", "0.00", "0.00", "SETTLED", "0.00"));
        assertThat(breaks).hasSize(1);
        assertThat(breaks.get(0).checkType()).isEqualTo(ConsistencyCheckType.SETTLEMENT_STATUS);
    }

    @Test
    void amount0的空单标OPEN_合法不报() {
        // amount=0、settled=0、OPEN：settled.signum()==0 → 合法（边界，不应误报）。
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkSettlementRollup(
                ar(5, "SINV-0", "0.00", "0.00", "OPEN", "0.00"));
        assertThat(breaks).isEmpty();
    }

    // ===================== 多条同时命中（三规则独立性） =====================

    @Test
    void 同时超额且rollup不符且状态错_三条各报一条() {
        // settled=1300（>amount=1250 超额；与记录 1200 不符；标 OPEN 但 settled>0 状态错）。
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkSettlementRollup(
                ap(7, "PINV-1", "1250.00", "1300.00", "OPEN", "1200.00"));
        assertThat(breaks).hasSize(3);
        assertThat(breaks).extracting(ConsistencyBreak::checkType)
                .containsExactlyInAnyOrder(
                        ConsistencyCheckType.SETTLEMENT_ROLLUP,
                        ConsistencyCheckType.SETTLEMENT_OVER,
                        ConsistencyCheckType.SETTLEMENT_STATUS);
        assertThat(breaks).allMatch(b -> b.severity() == ConsistencySeverity.ERROR);
        assertThat(breaks).allMatch(b -> "PINV-1#PAYABLE#7".equals(b.key()));
    }

    // ===================== M4-T07c 反向核销：负额记录纳入 Σ 后仍 0 ERROR =====================

    @Test
    void 反向核销后_负额记录纳入Σ_settled与真源仍一致_部分回退PARTIAL不报() {
        // settle 1000 后 unsettle 400：核销记录 Σ = 1000 + (-400) = 600 == 子账 settled；
        // 余额 = 1500-600 = 900 > 0、settled > 0 → PARTIAL 相符。三规则均不报。
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkSettlementRollup(
                ar(5, "SINV-1", "1500.00", "600.00", "PARTIAL", "600.00"));
        assertThat(breaks).isEmpty();
    }

    @Test
    void 反向核销全额回退后_Σ归零_状态回OPEN_不报() {
        // settle 1500 后 unsettle 1500：Σ = 1500 + (-1500) = 0 == 子账 settled=0；状态回 OPEN。
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkSettlementRollup(
                ar(5, "SINV-1", "1500.00", "0.00", "OPEN", "0.00"));
        assertThat(breaks).isEmpty();
    }

    @Test
    void 反向核销应付全额回退后_Σ归零_状态回OPEN_不报() {
        List<ConsistencyBreak> breaks = ConsistencyCheckService.checkSettlementRollup(
                ap(7, "PINV-1", "1250.00", "0.00", "OPEN", "0.00"));
        assertThat(breaks).isEmpty();
    }

    @Test
    void check_反向核销后子账纳入_报告干净_不阻塞月末关账() {
        // 端到端口径：应收 settle 1000 后 unsettle 400（Σ含负额=600=settled、PARTIAL）；
        // 应付 settle 后全额 unsettle 回退（Σ=0=settled、OPEN）——一致性闸门 0 ERROR。
        ConsistencyCheckDao dao = mock(ConsistencyCheckDao.class);
        when(dao.receivableRollups()).thenReturn(List.of(
                ar(5, "SINV-1", "1500.00", "600.00", "PARTIAL", "600.00")));
        when(dao.payableRollups()).thenReturn(List.of(
                ap(7, "PINV-1", "1250.00", "0.00", "OPEN", "0.00")));

        ConsistencyCheckService service = new ConsistencyCheckService(dao, false,
                Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC));
        ConsistencyReport report = service.check();

        assertThat(report.clean()).isTrue();
        assertThat(report.errorCount()).isEqualTo(0);
    }

    // ===================== check() 编排：rollup 行纳入报告 =====================

    @Test
    void check_应收应付rollup均纳入_全平报告干净() {
        ConsistencyCheckDao dao = mock(ConsistencyCheckDao.class);
        when(dao.receivableRollups()).thenReturn(List.of(
                ar(5, "SINV-1", "1500.00", "1500.00", "SETTLED", "1500.00")));
        when(dao.payableRollups()).thenReturn(List.of(
                ap(7, "PINV-1", "1250.00", "0.00", "OPEN", "0.00")));
        // 其余 DAO 方法 mock 默认返回空 List（Mockito RETURNS_DEFAULTS）。

        ConsistencyCheckService service = new ConsistencyCheckService(dao, false,
                Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC));
        ConsistencyReport report = service.check();

        assertThat(report.clean()).isTrue();
    }

    @Test
    void check_应收rollup不符与应付状态错_计入ERROR各一条() {
        ConsistencyCheckDao dao = mock(ConsistencyCheckDao.class);
        when(dao.receivableRollups()).thenReturn(List.of(
                ar(5, "SINV-1", "1500.00", "1001.00", "PARTIAL", "1000.00"))); // 规则8 ERROR
        when(dao.payableRollups()).thenReturn(List.of(
                ap(7, "PINV-1", "1250.00", "0.00", "SETTLED", "0.00")));        // 规则10 ERROR（余额>0 标 SETTLED）

        ConsistencyCheckService service = new ConsistencyCheckService(dao, false, Clock.systemUTC());
        ConsistencyReport report = service.check();

        assertThat(report.clean()).isFalse();
        assertThat(report.errorCount()).isEqualTo(2);
        assertThat(report.breaks()).extracting(ConsistencyBreak::checkType)
                .containsExactlyInAnyOrder(
                        ConsistencyCheckType.SETTLEMENT_ROLLUP,
                        ConsistencyCheckType.SETTLEMENT_STATUS);
    }
}
