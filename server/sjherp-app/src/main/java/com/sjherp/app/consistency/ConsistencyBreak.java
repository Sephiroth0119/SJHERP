package com.sjherp.app.consistency;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 单条勾稽差异记录（M3-T13 检查 Agent）。一条 break = 一处对不上的账。
 *
 * <p>五要素（docs 业务文档「数据一致性校验」§3「怎么读一条 break」）：
 * <ul>
 *   <li>{@code checkType}：命中的勾稽规则；</li>
 *   <li>{@code key}：出问题的对象（库存类是 {@code warehouse=1,product=2}；
 *       财务/单据类是单据号）；</li>
 *   <li>{@code expected}：按勾稽关系本应是多少（BigDecimal#toPlainString 承载，规避 JSON 数字误差）；</li>
 *   <li>{@code actual}：账本里实际是多少；</li>
 *   <li>{@code severity}：严重度；</li>
 *   <li>{@code message}：人读说明。</li>
 * </ul>
 *
 * <p>金额/数量一律 {@code toPlainString()} 承载为字符串（CLAUDE.md 精度原则：禁止 JSON 数字，
 * 也规避 BigDecimal 标度差异）。
 */
public record ConsistencyBreak(ConsistencyCheckType checkType, String key, String expected,
                               String actual, ConsistencySeverity severity, String message) {

    public ConsistencyBreak {
        Objects.requireNonNull(checkType, "checkType 不能为空");
        Objects.requireNonNull(severity, "severity 不能为空");
    }

    /** 便捷工厂：BigDecimal 期望/实际值用 toPlainString 承载（null 安全）。 */
    public static ConsistencyBreak of(ConsistencyCheckType checkType, String key,
                                      BigDecimal expected, BigDecimal actual,
                                      ConsistencySeverity severity, String message) {
        return new ConsistencyBreak(checkType, key, plain(expected), plain(actual), severity, message);
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
