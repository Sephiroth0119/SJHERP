package com.sjherp.app.llm;

import java.util.regex.Pattern;

/**
 * 发给第三方 LLM 前的基本脱敏（可选，默认关闭）。
 *
 * <p>只覆盖高置信模式：中国大陆手机号、邮箱、18 位身份证。金额/单号不脱敏——
 * Agent 做进销存必须看到数量金额，过度打码等于关掉聊天。
 */
public final class LlmRedactor {

    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)[1-9]\\d{16}[0-9Xx](?!\\d)");

    private LlmRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = MOBILE.matcher(text).replaceAll("[已脱敏-手机]");
        result = EMAIL.matcher(result).replaceAll("[已脱敏-邮箱]");
        return ID_CARD.matcher(result).replaceAll("[已脱敏-证件]");
    }
}
