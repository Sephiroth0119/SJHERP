package com.sjherp.domain.production;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.production.ProductionCostSettlementService.Allocation;

/**
 * 约当产量法纯函数单测（M5-T06，ADR §2）——无 IO、无 Spring、无 DB。
 *
 * <p>关键不变式：<b>完工应负担 + 在产应负担 == 本期工费总额</b>（尾差并入完工，无丢分，R-T06-5）；
 * 单位工费 = 总额 / 总约当产量；完工程度 0/50/100% 边界；无产出/无工费不除零。
 */
class EquivalentUnitsTest {

    private static Allocation alloc(String total, String completedQty, String wipQty, String pct) {
        return ProductionCostSettlementService.allocate(
                new BigDecimal(total), new BigDecimal(completedQty),
                new BigDecimal(wipQty), new BigDecimal(pct));
    }

    @Test
    void 全部完工_无在产_工费全归完工() {
        // 完工 10、在产 0：约当=10，单位=100/10=10，完工=100，在产=0
        Allocation a = alloc("100.00", "10", "0", "0");
        assertThat(a.completedLaborOverhead()).isEqualByComparingTo("100.00");
        assertThat(a.wipLaborOverhead()).isEqualByComparingTo("0.00");
        assertThat(a.totalEquivalent()).isEqualByComparingTo("10");
    }

    @Test
    void 完工与在产50pct_约当量与分摊正确_加总等于总额() {
        // 完工 10、在产 10 @50% → 在产约当 5，总约当 15，单位 = 150/15 = 10
        // 在产应负担 = 5 × 10 = 50，完工应负担 = 150 − 50 = 100（尾差并入完工）
        Allocation a = alloc("150.00", "10", "10", "50");
        assertThat(a.wipEquivalent()).isEqualByComparingTo("5");
        assertThat(a.totalEquivalent()).isEqualByComparingTo("15");
        assertThat(a.wipLaborOverhead()).isEqualByComparingTo("50.00");
        assertThat(a.completedLaborOverhead()).isEqualByComparingTo("100.00");
        // 不变式：两部分加总 == 总额
        assertThat(a.completedLaborOverhead().add(a.wipLaborOverhead()))
                .isEqualByComparingTo("150.00");
    }

    @Test
    void 在产100pct_视同完工约当量() {
        // 完工 0、在产 10 @100% → 在产约当 10，总约当 10，单位 = 100/10 = 10
        // 在产应负担 = 100，完工应负担 = 0
        Allocation a = alloc("100.00", "0", "10", "100");
        assertThat(a.wipEquivalent()).isEqualByComparingTo("10");
        assertThat(a.wipLaborOverhead()).isEqualByComparingTo("100.00");
        assertThat(a.completedLaborOverhead()).isEqualByComparingTo("0.00");
    }

    @Test
    void 在产0pct_工费全归完工() {
        // 完工 10、在产 10 @0% → 在产约当 0，全归完工
        Allocation a = alloc("100.00", "10", "10", "0");
        assertThat(a.wipEquivalent()).isEqualByComparingTo("0");
        assertThat(a.wipLaborOverhead()).isEqualByComparingTo("0.00");
        assertThat(a.completedLaborOverhead()).isEqualByComparingTo("100.00");
    }

    @Test
    void 工费为0_完工在产均0_不除零() {
        Allocation a = alloc("0.00", "10", "10", "50");
        assertThat(a.completedLaborOverhead()).isEqualByComparingTo("0.00");
        assertThat(a.wipLaborOverhead()).isEqualByComparingTo("0.00");
    }

    @Test
    void 无任何产出_总约当0_不除零_全归完工() {
        // 完工 0、在产 0：总约当 0，单位工费无意义，全部归完工（即 total），在产 0
        Allocation a = alloc("123.45", "0", "0", "0");
        assertThat(a.totalEquivalent()).isEqualByComparingTo("0");
        assertThat(a.completedLaborOverhead()).isEqualByComparingTo("123.45");
        assertThat(a.wipLaborOverhead()).isEqualByComparingTo("0.00");
    }

    @Test
    void 除不尽_尾差并入完工_加总仍等于总额() {
        // 完工 3、在产 0 @0% → 约当 3，单位 = 100/3 = 33.333333（6 位）
        // 在产 0；完工 = 总额 100（尾差并入完工）
        Allocation a = alloc("100.00", "3", "0", "0");
        assertThat(a.completedLaborOverhead()).isEqualByComparingTo("100.00");
        assertThat(a.wipLaborOverhead()).isEqualByComparingTo("0.00");

        // 除不尽且有在产：完工 2、在产 2 @50% → 在产约当 1，总约当 3，单位 = 100/3 = 33.333333
        // 在产应负担 = 1 × 33.333333 = 33.33（2 位），完工 = 100 − 33.33 = 66.67（尾差并入完工）
        Allocation b = alloc("100.00", "2", "2", "50");
        assertThat(b.wipLaborOverhead()).isEqualByComparingTo("33.33");
        assertThat(b.completedLaborOverhead()).isEqualByComparingTo("66.67");
        assertThat(b.completedLaborOverhead().add(b.wipLaborOverhead())).isEqualByComparingTo("100.00");
    }
}
