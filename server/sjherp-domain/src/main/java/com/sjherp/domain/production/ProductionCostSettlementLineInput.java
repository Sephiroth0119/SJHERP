package com.sjherp.domain.production;

import java.math.BigDecimal;

/**
 * 月末成本结转单建单行输入（M5-T06）。
 *
 * <p>每行指定一个待结转工单 + 期末在产数量与完工程度（在产工费约当量算法的输入，ADR §2）。
 * 料/工/费三要素金额与完工/在产分摊由 {@link ProductionCostSettlementService#create} 在
 * 装载工单/领料/报工/工艺路线后算出，<b>不由调用方传入</b>（防绕过领域计算）。
 *
 * <p>WIP 选取（D5）：调用方（向导/Agent）按账期挑出本期有完工或在产的工单逐行传入；
 * 在产数量 wipQty / 完工程度 wipCompletionPct 由用户录入（工单级单一百分比，R-T06-3）。
 * 全部完工的工单 wipQty 传 0、wipCompletionPct 传 0；无在产时本期工费全部归完工。
 *
 * @param workOrderDocNo    工单号（须存在且 EXECUTING/COMPLETED，由 Service 校验）
 * @param wipQty            期末在产数量（≥ 0，可为 null 默认 0；6 位小数）
 * @param wipCompletionPct  在产完工程度百分比（0–100，可为 null 默认 0；2 位小数）
 */
public record ProductionCostSettlementLineInput(
        String workOrderDocNo,
        BigDecimal wipQty,
        BigDecimal wipCompletionPct) {
}
