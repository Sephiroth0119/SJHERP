package com.sjherp.domain.common.numbering;

import java.time.YearMonth;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 单据编号规则值对象：前缀 + 年月 + 序号，如 PO-202606-0001。
 *
 * <p>不可变。序号按"前缀+年月"作用域独立递增（跨月自动重新从 1 开始），
 * 序号不足位数左补零，超出位数不截断（保证编号永不重复优先于格式美观）。
 */
public final class DocumentNumberRule {

    /** 默认序号位数：4 位（0001–9999，超出自然扩展） */
    public static final int DEFAULT_SEQUENCE_WIDTH = 4;

    /** 前缀约束：1–10 位大写字母（如 PO / SO / WO / VCH） */
    private static final Pattern PREFIX_PATTERN = Pattern.compile("[A-Z]{1,10}");

    private static final String SEPARATOR = "-";

    /** 单据类型前缀，如 PO（采购订单） */
    private final String prefix;

    /** 序号位数（不足左补零） */
    private final int sequenceWidth;

    private DocumentNumberRule(String prefix, int sequenceWidth) {
        Objects.requireNonNull(prefix, "prefix 不能为空");
        if (!PREFIX_PATTERN.matcher(prefix).matches()) {
            throw new IllegalArgumentException("单据编号前缀必须为 1-10 位大写字母: " + prefix);
        }
        if (sequenceWidth < 1 || sequenceWidth > 9) {
            throw new IllegalArgumentException("序号位数必须在 1-9 之间: " + sequenceWidth);
        }
        this.prefix = prefix;
        this.sequenceWidth = sequenceWidth;
    }

    /** 默认 4 位序号的规则，如 PO-202606-0001 */
    public static DocumentNumberRule of(String prefix) {
        return new DocumentNumberRule(prefix, DEFAULT_SEQUENCE_WIDTH);
    }

    public static DocumentNumberRule of(String prefix, int sequenceWidth) {
        return new DocumentNumberRule(prefix, sequenceWidth);
    }

    /**
     * 序号作用域键（前缀+年月，如 PO-202606）。
     * SequenceProvider 按此键独立计数，实现"序号按月重新开始"。
     */
    public String sequenceScopeKey(YearMonth yearMonth) {
        Objects.requireNonNull(yearMonth, "yearMonth 不能为空");
        return prefix + SEPARATOR + formatYearMonth(yearMonth);
    }

    /**
     * 按规则格式化完整单据号。
     *
     * @param yearMonth 年月
     * @param sequence  序号（必须 >= 1）
     * @return 如 PO-202606-0001；序号超出位数时不截断（如 5 位规则下第 100000 号为 PO-202606-100000）
     */
    public String format(YearMonth yearMonth, long sequence) {
        Objects.requireNonNull(yearMonth, "yearMonth 不能为空");
        if (sequence < 1) {
            throw new IllegalArgumentException("序号必须 >= 1: " + sequence);
        }
        String seq = String.valueOf(sequence);
        if (seq.length() < sequenceWidth) {
            seq = "0".repeat(sequenceWidth - seq.length()) + seq;
        }
        return prefix + SEPARATOR + formatYearMonth(yearMonth) + SEPARATOR + seq;
    }

    private static String formatYearMonth(YearMonth yearMonth) {
        return String.format("%04d%02d", yearMonth.getYear(), yearMonth.getMonthValue());
    }

    public String getPrefix() {
        return prefix;
    }

    public int getSequenceWidth() {
        return sequenceWidth;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DocumentNumberRule r
                && prefix.equals(r.prefix)
                && sequenceWidth == r.sequenceWidth;
    }

    @Override
    public int hashCode() {
        return Objects.hash(prefix, sequenceWidth);
    }

    @Override
    public String toString() {
        return "DocumentNumberRule{prefix=" + prefix + ", sequenceWidth=" + sequenceWidth + "}";
    }
}
