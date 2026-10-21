package com.sjherp.app.llm;

/**
 * 给前端/合规展示的 LLM 出境说明（出现在 {@code GET /api/auth/me}）。
 */
public record LlmEgressNotice(
        boolean enabled,
        boolean thirdParty,
        String provider,
        String destination,
        String notice,
        boolean redactEnabled,
        boolean localOnly) {
}
