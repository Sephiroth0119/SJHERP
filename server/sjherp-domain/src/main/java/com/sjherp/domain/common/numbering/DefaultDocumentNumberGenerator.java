package com.sjherp.domain.common.numbering;

import java.time.Clock;
import java.time.YearMonth;
import java.util.Objects;

/**
 * 默认单据编号生成器：组合编号规则与序号供给。
 *
 * <p>序号按"前缀+年月"作用域独立递增，跨月自动从 1 重新开始。
 * 并发安全与序号持久化由注入的 {@link SequenceProvider} 实现负责。
 * Clock 可注入，便于测试固定年月。
 */
public final class DefaultDocumentNumberGenerator implements DocumentNumberGenerator {

    private final SequenceProvider sequenceProvider;

    private final Clock clock;

    public DefaultDocumentNumberGenerator(SequenceProvider sequenceProvider) {
        this(sequenceProvider, Clock.systemDefaultZone());
    }

    public DefaultDocumentNumberGenerator(SequenceProvider sequenceProvider, Clock clock) {
        this.sequenceProvider = Objects.requireNonNull(sequenceProvider, "sequenceProvider 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    @Override
    public String generate(DocumentNumberRule rule) {
        return generate(rule, YearMonth.now(clock));
    }

    @Override
    public String generate(DocumentNumberRule rule, YearMonth yearMonth) {
        Objects.requireNonNull(rule, "rule 不能为空");
        Objects.requireNonNull(yearMonth, "yearMonth 不能为空");
        long sequence = sequenceProvider.next(rule.sequenceScopeKey(yearMonth));
        return rule.format(yearMonth, sequence);
    }
}
