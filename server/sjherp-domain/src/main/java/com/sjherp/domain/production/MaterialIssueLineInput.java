package com.sjherp.domain.production;

import java.math.BigDecimal;

/**
 * 领料单行输入值对象（建单时传入，M5-T04）。
 *
 * @param productId   子件商品 id
 * @param requiredQty 应领数量（计划量，含损耗；≥ 0，可为 0 表示仅记录）
 * @param quantity    实领数量（实际领取量，> 0）
 * @param unitId      计量单位 id
 */
public record MaterialIssueLineInput(long productId, BigDecimal requiredQty, BigDecimal quantity, long unitId) {
}
