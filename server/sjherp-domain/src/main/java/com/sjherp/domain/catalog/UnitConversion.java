package com.sjherp.domain.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 商品级多单位换算（值对象，从属 {@link Product} 聚合）。
 *
 * <p>语义：1 个换算单位 = {@code rate} 个基本单位。如商品基本单位为"瓶"、
 * 换算单位为"箱"、rate = 12，即 1 箱 = 12 瓶。
 *
 * <p>不可妥协原则 5：换算率与数量一律 {@link BigDecimal}，禁止 float/double。
 * 换算率精度上限 6 位小数（与数据库 DECIMAL(18,6) 对齐），除法统一 HALF_UP。
 *
 * @param unitId 换算单位 id（不得等于商品基本单位）
 * @param rate   换算率（1 换算单位 = rate 基本单位），必须 > 0
 */
public record UnitConversion(long unitId, BigDecimal rate) {

    /** 换算率最大小数位数（数据库列 DECIMAL(18,6)） */
    public static final int MAX_RATE_SCALE = 6;

    public UnitConversion {
        Objects.requireNonNull(rate, "换算率不能为空");
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("换算率必须大于 0: " + rate.toPlainString());
        }
        // stripTrailingZeros：1.230000 视为 2 位小数，不误判超限
        if (rate.stripTrailingZeros().scale() > MAX_RATE_SCALE) {
            throw new IllegalArgumentException(
                    "换算率小数位数不能超过 " + MAX_RATE_SCALE + " 位: " + rate.toPlainString());
        }
    }

    /**
     * 换算单位数量 → 基本单位数量（乘法无精度损失，不舍入）。
     * 如 3 箱 × 12 = 36 瓶。
     */
    public BigDecimal toBaseQuantity(BigDecimal quantity) {
        Objects.requireNonNull(quantity, "数量不能为空");
        return quantity.multiply(rate);
    }

    /**
     * 基本单位数量 → 换算单位数量（除法可能除不尽，按目标精度 HALF_UP 舍入）。
     * 如 1 瓶 ÷ 12 = 0.0833 箱（scale=4）。
     *
     * @param scale 结果保留小数位数（通常取换算单位的精度），必须 >= 0
     */
    public BigDecimal fromBaseQuantity(BigDecimal baseQuantity, int scale) {
        Objects.requireNonNull(baseQuantity, "数量不能为空");
        if (scale < 0) {
            throw new IllegalArgumentException("精度必须 >= 0: " + scale);
        }
        return baseQuantity.divide(rate, scale, RoundingMode.HALF_UP);
    }
}
