package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 工艺路线工序（值对象，从属 {@link Routing} 聚合）。
 *
 * <p>工序按 sequenceNo 有序排列，同 routingId 内 sequenceNo 唯一。
 * work_center 和 cost_rate 为预留字段，T06 成本归集时填用，本批可空。
 *
 * <p>原则 5：工时/费率一律 {@link BigDecimal}（数据库 DECIMAL），禁止 float/double。
 *
 * @param sequenceNo     工序序号（同路线内唯一有序，正整数）
 * @param operationName  工序名称（不可为空）
 * @param standardHours  单位产品标准工时（必须 &gt; 0，小数位 ≤ 6）
 * @param workCenter     工作中心（可空，预留 T06 成本归集）
 * @param costRate       费率（可空，预留 T06，单位：元/工时）
 */
public record RoutingOperation(
        int sequenceNo,
        String operationName,
        BigDecimal standardHours,
        String workCenter,
        BigDecimal costRate) {

    /** 工时最大小数位数（数据库列 DECIMAL(18,6)） */
    public static final int MAX_HOURS_SCALE = 6;

    public RoutingOperation {
        if (sequenceNo <= 0) {
            throw new IllegalArgumentException("工序序号必须为正整数: " + sequenceNo);
        }
        Objects.requireNonNull(operationName, "工序名称不能为空");
        if (operationName.isBlank()) {
            throw new IllegalArgumentException("工序名称不能为空白");
        }
        Objects.requireNonNull(standardHours, "标准工时不能为空");
        if (standardHours.signum() <= 0) {
            throw new IllegalArgumentException("标准工时必须大于 0: " + standardHours.toPlainString());
        }
        if (standardHours.stripTrailingZeros().scale() > MAX_HOURS_SCALE) {
            throw new IllegalArgumentException(
                    "标准工时小数位数不能超过 " + MAX_HOURS_SCALE + " 位: " + standardHours.toPlainString());
        }
        // costRate 可空，非空时须 >= 0
        if (costRate != null && costRate.signum() < 0) {
            throw new IllegalArgumentException("费率不能为负数: " + costRate.toPlainString());
        }
    }
}
