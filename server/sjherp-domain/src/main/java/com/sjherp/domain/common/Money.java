package com.sjherp.domain.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 金额值对象（示意性骨架）。
 *
 * <p>不可妥协原则 5：金额一律 BigDecimal（数据库 DECIMAL），
 * 禁止 float/double 参与任何金额/数量/成本运算。本类刻意不提供
 * 接收 double 的构造方式。统一保留 2 位小数、四舍五入（HALF_UP）。
 */
public final class Money {

    /** 金额统一精度：2 位小数 */
    public static final int SCALE = 2;

    public static final Money ZERO = new Money(BigDecimal.ZERO);

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static Money of(BigDecimal amount) {
        return new Money(Objects.requireNonNull(amount, "金额不能为空"));
    }

    /** 从字符串构造（如来自表单 DECIMAL 字段），避免任何浮点中转 */
    public static Money of(String amount) {
        return new Money(new BigDecimal(Objects.requireNonNull(amount, "金额不能为空")));
    }

    public Money add(Money other) {
        return new Money(this.amount.add(other.amount));
    }

    public Money subtract(Money other) {
        return new Money(this.amount.subtract(other.amount));
    }

    /** 冲销用：取反金额（红字） */
    public Money negate() {
        return new Money(this.amount.negate());
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public BigDecimal value() {
        return amount;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Money m && this.amount.compareTo(m.amount) == 0;
    }

    @Override
    public int hashCode() {
        return amount.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
