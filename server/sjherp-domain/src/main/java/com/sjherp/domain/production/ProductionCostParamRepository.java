package com.sjherp.domain.production;

import java.util.Optional;

/**
 * 生产成本参数仓储接口（M5-T06，端口，实现在 infra 层）。
 * 只读取（参数维护本批从简，由迁移/直插或后续维护界面写入）。
 */
public interface ProductionCostParamRepository {

    /** 按账期查参数（不存在返回 empty，由 Service 决定是否用系统默认兜底） */
    Optional<ProductionCostParam> findByPeriod(String period);
}
