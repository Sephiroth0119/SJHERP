package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 齐套检查服务（M5-T04，只读，不锁库存，仅提示不阻断）。
 *
 * <p>按工单 active BOM <b>单层直接子件</b>需求（plannedQty × grossQuantity 含损耗）
 * 对比领料仓 onHand 可用量，输出缺料清单与齐套布尔。
 *
 * <p>半成品由各自工单生产，本检查只取直接子件（单层）。
 * 不预留库存（小企业简化）；领料不强制齐套（过账时才强校验库存余量）。
 */
public class KittingCheckService {

    private final BillOfMaterialsRepository bomRepository;
    private final InventoryAvailabilityPort inventoryAvailability;

    public KittingCheckService(BillOfMaterialsRepository bomRepository,
                                InventoryAvailabilityPort inventoryAvailability) {
        this.bomRepository = Objects.requireNonNull(bomRepository, "bomRepository 不能为空");
        this.inventoryAvailability = Objects.requireNonNull(inventoryAvailability, "inventoryAvailability 不能为空");
    }

    /**
     * 执行齐套检查（只读，不产生任何写操作）。
     *
     * @param workOrder   被检查的工单（须已开工/下达，工单 plannedQty 作净父件需求）
     * @param warehouseId 检查仓库 id（通常为领料仓）
     * @return 齐套检查结果（含各子件 required/available/shortage + 整体 kitted 标志）
     */
    public KittingCheck check(WorkOrder workOrder, long warehouseId) {
        Objects.requireNonNull(workOrder, "工单不能为空");

        // 查询 active BOM（单层直接子件）
        BillOfMaterials bom = bomRepository.findActiveByProductId(workOrder.getProductId())
                .orElse(null);

        if (bom == null || bom.getLines().isEmpty()) {
            // 无 active BOM：视为无物料需求，齐套为 true，返回空行列表
            return new KittingCheck(workOrder.getDocNo(), warehouseId, true, List.of());
        }

        BigDecimal plannedQty = workOrder.getPlannedQty();
        List<KittingCheckLine> lines = new ArrayList<>(bom.getLines().size());
        boolean kitted = true;

        for (BomLine bomLine : bom.getLines()) {
            // 子件净需求 = plannedQty × 行用量；毛需求再按损耗率加成（grossQuantity 只乘 1+scrapRate）
            BigDecimal childNetQty = plannedQty.multiply(bomLine.quantity());
            BigDecimal required = bomLine.grossQuantity(childNetQty)
                    .setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING);

            // 当前结存
            BigDecimal available = inventoryAvailability.onHand(warehouseId, bomLine.childProductId());

            // 缺料量 = max(required - available, 0)
            BigDecimal shortage = required.subtract(available);
            if (shortage.signum() < 0) {
                shortage = BigDecimal.ZERO;
            }

            if (shortage.signum() > 0) {
                kitted = false;
            }

            lines.add(new KittingCheckLine(bomLine.childProductId(), bomLine.unitId(),
                    required, available, shortage));
        }

        return new KittingCheck(workOrder.getDocNo(), warehouseId, kitted, lines);
    }
}
