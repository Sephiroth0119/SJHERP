package com.sjherp.domain.production;

import java.math.BigDecimal;

/**
 * 工序命令（嵌套在 {@link RoutingCommand} 中）。
 *
 * @param sequenceNo    工序序号（正整数，同路线内唯一）
 * @param operationName 工序名称
 * @param standardHours 标准工时（&gt; 0，BigDecimal）
 * @param workCenter    工作中心（可空）
 * @param costRate      费率（可空，元/工时）
 */
public record RoutingOperationCommand(
        int sequenceNo,
        String operationName,
        BigDecimal standardHours,
        String workCenter,
        BigDecimal costRate) {
}
