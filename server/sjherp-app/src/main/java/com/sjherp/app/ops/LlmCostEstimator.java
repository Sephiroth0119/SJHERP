package com.sjherp.app.ops;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * LLM 成本估算：仅在两端单价都配置时用 BigDecimal 计算，否则返回 empty（禁止用 0 冒充账单）。
 */
public final class LlmCostEstimator {

    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private static final int SCALE = 4;

    private LlmCostEstimator() {
    }

    public static Optional<BigDecimal> estimate(
            long promptTokens,
            long completionTokens,
            String promptPricePerMillion,
            String completionPricePerMillion) {
        Optional<BigDecimal> promptPrice = parsePrice(promptPricePerMillion);
        Optional<BigDecimal> completionPrice = parsePrice(completionPricePerMillion);
        if (promptPrice.isEmpty() || completionPrice.isEmpty()) {
            return Optional.empty();
        }
        if (promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("token 数不能为负");
        }
        BigDecimal promptCost = new BigDecimal(promptTokens)
                .multiply(promptPrice.get())
                .divide(MILLION, SCALE, RoundingMode.HALF_UP);
        BigDecimal completionCost = new BigDecimal(completionTokens)
                .multiply(completionPrice.get())
                .divide(MILLION, SCALE, RoundingMode.HALF_UP);
        return Optional.of(promptCost.add(completionCost).setScale(SCALE, RoundingMode.HALF_UP));
    }

    static Optional<BigDecimal> parsePrice(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String stripped = raw.strip();
        if (stripped.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal value;
        try {
            value = new BigDecimal(stripped);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("LLM 单价必须是十进制数字：" + stripped);
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("LLM 单价不能为负");
        }
        return Optional.of(value);
    }
}
