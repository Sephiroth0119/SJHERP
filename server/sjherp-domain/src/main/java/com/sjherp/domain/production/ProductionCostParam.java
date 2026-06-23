package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 生产成本参数（M5-T06，D1 落地，R-T06-1）。
 *
 * <p>按账期维护的两个静态费率参数：
 * <ul>
 *   <li>{@code defaultLaborRate}：默认人工费率（元/工时）——工序无 {@code costRate} 时兜底；</li>
 *   <li>{@code overheadRate}：制造费用率（元/工时，单一标准）——非真实费用池÷总工时，无费用归集来源。</li>
 * </ul>
 *
 * <p>原则 5：费率一律 {@link BigDecimal}（DECIMAL(18,6)），禁 float/double。
 *
 * @param period           账期键 yyyyMM
 * @param defaultLaborRate 默认人工费率（元/工时，≥ 0，6 位）
 * @param overheadRate     制造费用率（元/工时，≥ 0，6 位）
 */
public record ProductionCostParam(String period, BigDecimal defaultLaborRate, BigDecimal overheadRate) {

    public ProductionCostParam {
        Objects.requireNonNull(period, "账期不能为空");
        Objects.requireNonNull(defaultLaborRate, "默认人工费率不能为空");
        Objects.requireNonNull(overheadRate, "制造费用率不能为空");
        if (defaultLaborRate.signum() < 0) {
            throw new IllegalArgumentException("默认人工费率不能为负: " + defaultLaborRate.toPlainString());
        }
        if (overheadRate.signum() < 0) {
            throw new IllegalArgumentException("制造费用率不能为负: " + overheadRate.toPlainString());
        }
    }
}
