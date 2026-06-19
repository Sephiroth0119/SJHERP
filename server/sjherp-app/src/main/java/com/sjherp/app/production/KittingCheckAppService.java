package com.sjherp.app.production;

import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.production.KittingCheck;
import com.sjherp.domain.production.KittingCheckService;
import com.sjherp.domain.production.WorkOrder;
import com.sjherp.domain.production.WorkOrderService;

/**
 * 齐套检查应用服务（M5-T04）。
 *
 * <p>齐套检查跨两个领域服务——先从工单仓储加载工单，再调 KittingCheckService 只读计算。
 * 以只读事务包装，保证一致性快照。
 */
public class KittingCheckAppService {

    private final WorkOrderService workOrderService;
    private final KittingCheckService kittingCheckService;

    public KittingCheckAppService(WorkOrderService workOrderService,
                                  KittingCheckService kittingCheckService) {
        this.workOrderService = workOrderService;
        this.kittingCheckService = kittingCheckService;
    }

    /**
     * 对指定工单和仓库执行齐套检查（只读，不写任何状态）。
     *
     * @param workOrderDocNo 工单单号
     * @param warehouseId    领料仓 id
     * @return 齐套检查结果
     */
    @Transactional(readOnly = true)
    public KittingCheck check(String workOrderDocNo, long warehouseId) {
        WorkOrder workOrder = workOrderService.get(workOrderDocNo);
        return kittingCheckService.check(workOrder, warehouseId);
    }
}
