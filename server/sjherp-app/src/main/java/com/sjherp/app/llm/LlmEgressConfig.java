package com.sjherp.app.llm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册 LLM 出境配置，供 {@link LlmEgressService} 与聊天装配共用。 */
@Configuration
@EnableConfigurationProperties(LlmEgressProperties.class)
public class LlmEgressConfig {
}
