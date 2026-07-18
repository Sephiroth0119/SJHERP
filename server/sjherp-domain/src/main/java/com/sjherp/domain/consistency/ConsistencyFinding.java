package com.sjherp.domain.consistency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** 一致性检查发现的不可变差异明细。 */
public record ConsistencyFinding(int sequenceNo, String ruleCode, String checkType,
                                 String objectKey, BigDecimal expectedValue, BigDecimal actualValue,
                                 Severity severity, String message) {

    public enum Severity { ERROR, WARN, INFO }

    public ConsistencyFinding {
        if (sequenceNo < 1) {
            throw new IllegalArgumentException("差异序号必须为正数");
        }
        ruleCode = requireText(ruleCode, 64, "规则编码");
        checkType = requireText(checkType, 64, "检查类型");
        objectKey = optionalText(objectKey, 256, "对象键");
        validateDecimal(expectedValue, "预期值");
        validateDecimal(actualValue, "实际值");
        severity = Objects.requireNonNull(severity, "严重度不能为空");
        message = optionalText(message, 1000, "差异说明");
    }

    private static String requireText(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private static String optionalText(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(value, maxLength, fieldName);
    }

    private static void validateDecimal(BigDecimal value, String fieldName) {
        if (value == null) {
            return;
        }
        try {
            BigDecimal scaled = value.setScale(6, RoundingMode.UNNECESSARY);
            if (scaled.precision() > 24) {
                throw new IllegalArgumentException(fieldName + "不能超过 DECIMAL(24,6) 范围");
            }
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(fieldName + "必须精确表示为最多 6 位小数", ex);
        }
    }
}
