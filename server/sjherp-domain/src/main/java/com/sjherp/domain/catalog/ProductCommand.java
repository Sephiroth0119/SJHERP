package com.sjherp.domain.catalog;

import java.util.List;

/**
 * 商品创建/更新命令（领域服务入参，字段级格式校验在 {@link Product} 内完成）。
 *
 * @param code            商品编码；创建时为空表示自动编号（SKU-年月-序号）
 * @param name            商品名称（必填）
 * @param spec            规格型号，可空
 * @param categoryId      所属类目 id，可空
 * @param baseUnitId      基本单位 id（必填）
 * @param barcode         条码，可空
 * @param remark          备注，可空
 * @param unitConversions 多单位换算表，可空（视为无换算）
 * @param inventoryCategory 存货分类（必填，会计科目由应用层策略映射）
 */
public record ProductCommand(String code, String name, String spec, Long categoryId, Long baseUnitId,
                             String barcode, String remark, List<UnitConversion> unitConversions,
                             InventoryCategory inventoryCategory) {
}
