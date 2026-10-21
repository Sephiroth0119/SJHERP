package com.sjherp.infra.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 守门：Flyway 业务表不出现明文 API Key / 访问密钥列。密码哈希（BCrypt）与流程租约 token 例外。
 * 若新增此类列，必须走 {@link AesGcmSecretEncryptor} 并先更新本测试白名单。
 */
class SensitiveSchemaGuardTest {

    private static final Pattern CREATE_OR_ALTER_COLUMN = Pattern.compile(
            "(?i)(?:create\\s+table[\\s\\S]*?\\(|,\\s*|\\s+)([a-z0-9_]+)\\s+(?:varchar|text|char|varbinary|blob)");

    private static final List<String> FORBIDDEN = List.of(
            "api_key", "apikey", "access_token", "access_secret", "client_secret",
            "private_key", "llm_key", "llm_api_key", "github_token");

    private static final List<String> ALLOWED = List.of(
            "password_hash", "lease_token");

    @Test
    void flyway脚本不含明文密钥列() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:db/migration/V*.sql");
        assertThat(resources).isNotEmpty();
        List<String> offenders = new ArrayList<>();
        for (Resource resource : resources) {
            String sql;
            try (InputStream in = resource.getInputStream()) {
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Matcher matcher = CREATE_OR_ALTER_COLUMN.matcher(sql);
            while (matcher.find()) {
                String column = matcher.group(1).toLowerCase(Locale.ROOT);
                if (ALLOWED.contains(column)) {
                    continue;
                }
                if (FORBIDDEN.contains(column) || column.endsWith("_api_key") || column.endsWith("_secret")) {
                    offenders.add(resource.getFilename() + ":" + column);
                }
            }
        }
        assertThat(offenders)
                .as("数据库不应持久化明文第三方密钥；JWT/DB/LLM key 放 deploy/.env")
                .isEmpty();
    }
}
