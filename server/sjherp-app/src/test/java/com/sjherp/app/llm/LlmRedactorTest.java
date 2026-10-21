package com.sjherp.app.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmRedactorTest {

    @Test
    void 手机邮箱身份证被替换_金额保留() {
        String raw = "客户张三 手机13812345678 邮箱zhang@example.com 身份证110101199001011234 订单金额12800.50";
        String redacted = LlmRedactor.redact(raw);
        assertThat(redacted)
                .contains("[已脱敏-手机]")
                .contains("[已脱敏-邮箱]")
                .contains("[已脱敏-证件]")
                .contains("12800.50")
                .doesNotContain("13812345678")
                .doesNotContain("zhang@example.com");
    }

    @Test
    void 空值原样返回() {
        assertThat(LlmRedactor.redact(null)).isNull();
        assertThat(LlmRedactor.redact("")).isEmpty();
    }
}
