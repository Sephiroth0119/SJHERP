package com.sjherp.domain.partner;

import java.math.BigDecimal;

/**
 * 客户创建/更新命令（领域服务入参，字段级格式校验在 {@link Customer} 内完成）。
 *
 * @param code             客户编码；创建时为空表示自动编号（CUS-年月-序号）
 * @param name             客户名称（必填）
 * @param contactPerson    联系人，可空
 * @param contactPhone     联系电话，可空
 * @param address          地址，可空
 * @param taxNo            税号，可空
 * @param settlementMethod 结算方式（必填）：月结/现结/预付
 * @param creditLimit      信用额度（BigDecimal，可空表示不设限，不可为负；超限校验留 M3）
 */
public record CustomerCommand(String code, String name, String contactPerson, String contactPhone,
                              String address, String taxNo, SettlementMethod settlementMethod,
                              BigDecimal creditLimit) {
}
