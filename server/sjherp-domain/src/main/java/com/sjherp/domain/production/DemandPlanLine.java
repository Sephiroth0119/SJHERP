package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 需求计划行值对象（M5-T02）。
 *
 * <p>quantity 精度 6 位，大于 0 校验在命令层执行；unitId 为计划行所用单位（可非基本单位）。
 * dueDate 可空（手工预测可无需求截止日）。
 */
public record DemandPlanLine(
        long productId,
        BigDecimal quantity,
        long unitId,
        LocalDate dueDate) {
}
