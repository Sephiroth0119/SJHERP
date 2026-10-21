package com.sjherp.app.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class LlmCostEstimatorTest {

    @Test
    void 两端单价都配才估算_四舍五入到4位() {
        Optional<BigDecimal> cost = LlmCostEstimator.estimate(
                1_000_000, 500_000, "2.00", "8.00");
        assertThat(cost).contains(new BigDecimal("6.0000"));
    }

    @Test
    void 缺任一侧单价_不返回零金额() {
        assertThat(LlmCostEstimator.estimate(100, 20, "", "8")).isEmpty();
        assertThat(LlmCostEstimator.estimate(100, 20, "2", null)).isEmpty();
        assertThat(LlmCostEstimator.estimate(100, 20, "  ", " ")).isEmpty();
    }

    @Test
    void 零token且有单价_得到零而不是空() {
        assertThat(LlmCostEstimator.estimate(0, 0, "2", "8")).contains(new BigDecimal("0.0000"));
    }

    @Test
    void 非法单价抛错() {
        assertThatThrownBy(() -> LlmCostEstimator.estimate(1, 1, "abc", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("十进制");
        assertThatThrownBy(() -> LlmCostEstimator.estimate(1, 1, "-1", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为负");
    }
}
