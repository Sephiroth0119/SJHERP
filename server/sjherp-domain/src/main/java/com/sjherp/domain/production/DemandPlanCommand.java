package com.sjherp.domain.production;

import java.time.LocalDate;
import java.util.List;

/**
 * 创建/更新需求计划命令（M5-T02）。
 *
 * @param planDate 计划日期
 * @param remark   备注（可空）
 * @param lines    需求行列表（至少一行）
 */
public record DemandPlanCommand(
        LocalDate planDate,
        String remark,
        List<DemandPlanLineCommand> lines) {
}
