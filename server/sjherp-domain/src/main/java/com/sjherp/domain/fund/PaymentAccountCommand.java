package com.sjherp.domain.fund;

/**
 * 资金账户创建/更新命令（领域服务入参，字段级格式校验在 {@link PaymentAccount} 内完成，
 * glAccountCode 语义合法性在 {@link PaymentAccountService} 校验）。
 *
 * @param code          资金账户编码；创建时为空表示自动编号（FA-年月-序号）
 * @param name          账户名称（必填）
 * @param accountType   账户类别（必填）：CASH 现金 / BANK 银行 / OTHER 其他货币资金
 * @param glAccountCode 映射的 GL 货币科目编码（必填，须为已存在/启用/末级科目，如 1001/1002/1012）
 * @param bankName      开户行，可空（BANK 账户用）
 * @param accountNo     银行账号，可空
 */
public record PaymentAccountCommand(String code, String name, PaymentAccountType accountType,
                                    String glAccountCode, String bankName, String accountNo) {
}
