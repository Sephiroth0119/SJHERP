package com.sjherp.domain.fund;

/**
 * 资金账户类别（M4-T04a）：现金 / 银行 / 其他货币资金。
 *
 * <p>类别仅作分类与界面提示之用，实际过账借/贷的 GL 货币科目由账户
 * {@code glAccountCode} 字段决定（CASH 常映射 1001 库存现金、BANK 映射 1002
 * 银行存款、OTHER 映射 1012 其他货币资金），不由本枚举硬绑定，便于一个企业
 * 多张银行账户共用 1002 等灵活映射。
 */
public enum PaymentAccountType {

    /** 库存现金 */
    CASH("库存现金"),

    /** 银行存款 */
    BANK("银行存款"),

    /** 其他货币资金 */
    OTHER("其他货币资金");

    private final String label;

    PaymentAccountType(String label) {
        this.label = label;
    }

    /** 中文标签（审计摘要 / 用户可见文案统一出口） */
    public String label() {
        return label;
    }
}
