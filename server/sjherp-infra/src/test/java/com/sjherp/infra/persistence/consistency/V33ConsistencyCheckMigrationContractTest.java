package com.sjherp.infra.persistence.consistency;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class V33ConsistencyCheckMigrationContractTest {

    private static final String V6 = migration("V6__identity.sql");
    private static final String V33 = migration("V33__consistency_check_framework.sql");

    @Test
    void childRelationshipsAreTenantScopedWithMatchingParentKeys() {
        assertThat(V33)
                .contains("UNIQUE KEY uk_consistency_check_run_tenant_id (tenant_id, id)")
                .contains("ALTER TABLE sys_user\n"
                        + "    ADD UNIQUE KEY uk_sys_user_tenant_id (tenant_id, id)")
                .containsPattern("FOREIGN KEY \\(tenant_id, run_id\\)\\s+"
                        + "REFERENCES consistency_check_run \\(tenant_id, id\\)")
                .containsPattern("FOREIGN KEY \\(tenant_id, recipient_user_id\\)\\s+"
                        + "REFERENCES sys_user \\(tenant_id, id\\)")
                .doesNotContain("FOREIGN KEY (run_id)")
                .doesNotContain("FOREIGN KEY (recipient_user_id)");
    }

    @Test
    void foreignKeyColumnTypesMatchIncludingUnsignedness() {
        assertThat(columnType(V33, "consistency_check_break", "tenant_id"))
                .isEqualTo(columnType(V33, "consistency_check_run", "tenant_id"));
        assertThat(columnType(V33, "consistency_check_break", "run_id"))
                .isEqualTo(columnType(V33, "consistency_check_run", "id"));
        assertThat(columnType(V33, "system_notification", "tenant_id"))
                .isEqualTo(columnType(V6, "sys_user", "tenant_id"));
        assertThat(columnType(V33, "system_notification", "recipient_user_id"))
                .isEqualTo(columnType(V6, "sys_user", "id"))
                .isEqualTo("BIGINT UNSIGNED");
    }

    private static String columnType(String sql, String table, String column) {
        Pattern tablePattern = Pattern.compile(
                "CREATE TABLE\\s+" + Pattern.quote(table) + "\\s*\\((.*?)\\)\\s*ENGINE",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher tableMatcher = tablePattern.matcher(sql);
        assertThat(tableMatcher.find()).as("table %s exists", table).isTrue();

        Pattern columnPattern = Pattern.compile(
                "(?im)^\\s*" + Pattern.quote(column) + "\\s+(BIGINT(?:\\s+UNSIGNED)?)\\b");
        Matcher columnMatcher = columnPattern.matcher(tableMatcher.group(1));
        assertThat(columnMatcher.find()).as("column %s.%s exists", table, column).isTrue();
        return columnMatcher.group(1).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static String migration(String fileName) {
        String resource = "db/migration/" + fileName;
        try (InputStream input = V33ConsistencyCheckMigrationContractTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing migration: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read migration: " + resource, exception);
        }
    }
}
