package com.sjherp.app.llm;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.sjherp.app.config.LlmProperties;
import com.sjherp.app.config.LlmProperties.ProviderConfig;

/**
 * 计算当前部署是否把聊天业务数据发往第三方模型，并生成可展示告知。
 */
@Service
public class LlmEgressService {

    private final LlmProperties llmProperties;
    private final LlmEgressProperties egressProperties;
    private final String agentMode;

    public LlmEgressService(LlmProperties llmProperties,
                            LlmEgressProperties egressProperties,
                            @Value("${sjherp.agent.mode:auto}") String agentMode) {
        this.llmProperties = llmProperties;
        this.egressProperties = egressProperties;
        this.agentMode = agentMode == null ? "auto" : agentMode;
    }

    public LlmEgressNotice current() {
        boolean placeholderForced = "placeholder".equals(agentMode.strip().toLowerCase(Locale.ROOT));
        boolean llmActive = !placeholderForced && llmProperties.roleHasApiKey(LlmProperties.ROLE_CHAT);
        ProviderConfig chat = llmProperties.providerForRole(LlmProperties.ROLE_CHAT);
        String provider = llmActive ? llmProperties.providerNameForRole(LlmProperties.ROLE_CHAT) : null;
        String destination = llmActive && chat != null ? chat.baseUrl() : null;
        boolean thirdParty = llmActive && LlmDestinations.isThirdParty(destination);
        String notice = null;
        if (llmActive && thirdParty && egressProperties.noticeEnabled()) {
            String dest = destination == null ? "第三方模型" : destination;
            String name = provider == null ? "已配置的 LLM" : provider;
            StringBuilder text = new StringBuilder("当前对话会把业务内容发送到第三方模型（")
                    .append(name).append(" / ").append(dest)
                    .append("）。可在 deploy/.env 把 provider 改到本地 OpenAI 兼容端点（如 Ollama），")
                    .append("或设置 SJHERP_LLM_REDACT=true 做手机号/邮箱/身份证脱敏，")
                    .append("或 SJHERP_LLM_LOCAL_ONLY=true 禁止出网。");
            if (egressProperties.redactEnabled()) {
                text.append("已开启基本脱敏。");
            }
            notice = text.toString();
        }
        return new LlmEgressNotice(
                llmActive,
                thirdParty,
                provider,
                destination,
                notice,
                egressProperties.redactEnabled(),
                egressProperties.localOnly());
    }
}
