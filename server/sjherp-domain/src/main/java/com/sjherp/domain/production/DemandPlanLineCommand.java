package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 需求计划行命令值对象（M5-T02）。
 *
 * @param productId 商品 id
 * @param quantity  需求数量（必须 &gt; 0，精度 ≤ 6）
 * @param unitId    单位 id
 * @param dueDate   需求截止日期（可空）
 */
public record DemandPlanLineCommand(
        long productId,
        BigDecimal quantity,
        long unitId,
        LocalDate dueDate) {
}
