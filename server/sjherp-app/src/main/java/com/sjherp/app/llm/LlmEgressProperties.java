package com.sjherp.app.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LLM 数据出境开关（M8-T03 / Q-7，前缀 {@code sjherp.llm.egress}）。
 *
 * @param noticeEnabled 是否在 /me 与聊天界面展示第三方模型告知
 * @param redactEnabled 发送给模型前是否做手机号/邮箱/身份证等基本脱敏
 * @param localOnly     为 true 时拒绝指向公网第三方的 chat provider（启动失败）
 */
@ConfigurationProperties(prefix = "sjherp.llm.egress")
public record LlmEgressProperties(
        @DefaultValue("true") boolean noticeEnabled,
        @DefaultValue("false") boolean redactEnabled,
        @DefaultValue("false") boolean localOnly) {
}
