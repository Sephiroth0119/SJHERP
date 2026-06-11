package com.sjherp.domain.partner;

/**
 * 供应商创建/更新命令（领域服务入参，字段级格式校验在 {@link Supplier} 内完成）。
 *
 * @param code             供应商编码；创建时为空表示自动编号（SUP-年月-序号）
 * @param name             供应商名称（必填）
 * @param contactPerson    联系人，可空
 * @param contactPhone     联系电话，可空
 * @param address          地址，可空
 * @param taxNo            税号，可空
 * @param settlementMethod 结算方式（必填）：月结/现结/预付
 */
public record SupplierCommand(String code, String name, String contactPerson, String contactPhone,
                              String address, String taxNo, SettlementMethod settlementMethod) {
}
