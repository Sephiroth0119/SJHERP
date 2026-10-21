package com.sjherp.app.llm;

import java.net.URI;
import java.util.Locale;

/**
 * 判断 LLM base-url 是否把业务数据送出本机/内网。
 */
public final class LlmDestinations {

    private LlmDestinations() {
    }

    public static boolean isThirdParty(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(baseUrl.strip());
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return true;
            }
            String normalized = host.strip().toLowerCase(Locale.ROOT);
            if ("localhost".equals(normalized)
                    || "127.0.0.1".equals(normalized)
                    || "0.0.0.0".equals(normalized)
                    || "::1".equals(normalized)
                    || normalized.endsWith(".local")
                    || normalized.endsWith(".localhost")) {
                return false;
            }
            return !isPrivateIpv4(normalized);
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static boolean isPrivateIpv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            if (a == 10) {
                return true;
            }
            if (a == 192 && b == 168) {
                return true;
            }
            if (a == 172 && b >= 16 && b <= 31) {
                return true;
            }
            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
