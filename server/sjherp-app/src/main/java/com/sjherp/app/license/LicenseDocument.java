package com.sjherp.app.license;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 离线 license 文件（JSON + Ed25519 签名）。签名对象是固定字段顺序的 canonical 文本，
 * 不依赖 JSON 键序，便于 Java 与 {@code openssl pkeyutl} 互认。
 */
public record LicenseDocument(
        int v,
        String subject,
        LocalDate issuedAt,
        LocalDate expiresAt,
        Integer seats,
        List<String> modules,
        Integer graceDays,
        String kid,
        String sig) {

    public static final int VERSION = 1;
    public static final String CANONICAL_HEADER = "SJHERP-LICENSE-V1";

    private static final ObjectMapper JSON = new ObjectMapper();

    public LicenseDocument {
        if (v != VERSION) {
            throw new LicenseFormatException("仅支持 license v=" + VERSION);
        }
        if (subject == null || subject.isBlank()) {
            throw new LicenseFormatException("license 缺少签发对象 subject");
        }
        if (issuedAt == null) {
            throw new LicenseFormatException("license 缺少签发日 issuedAt");
        }
        subject = subject.strip();
        if (seats != null && seats <= 0) {
            throw new LicenseFormatException("席位 seats 必须为正整数");
        }
        if (graceDays != null && graceDays < 0) {
            throw new LicenseFormatException("宽限期 graceDays 不能为负");
        }
        modules = normalizeModules(modules);
        kid = blankToNull(kid);
        sig = blankToNull(sig);
    }

    public LicenseDocument withSignature(String signature) {
        return new LicenseDocument(v, subject, issuedAt, expiresAt, seats, modules, graceDays, kid, signature);
    }

    public byte[] canonicalBytes() {
        StringBuilder text = new StringBuilder();
        appendLine(text, CANONICAL_HEADER);
        appendLine(text, "v=" + v);
        appendLine(text, "subject=" + subject);
        appendLine(text, "issuedAt=" + issuedAt);
        appendLine(text, "expiresAt=" + (expiresAt == null ? "" : expiresAt));
        appendLine(text, "seats=" + (seats == null ? "" : seats));
        appendLine(text, "modules=" + String.join(",", modules));
        appendLine(text, "graceDays=" + (graceDays == null ? "" : graceDays));
        appendLine(text, "kid=" + (kid == null ? "" : kid));
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    public String toJson() {
        ObjectNode node = JSON.createObjectNode();
        node.put("v", v);
        node.put("subject", subject);
        node.put("issuedAt", issuedAt.toString());
        if (expiresAt != null) {
            node.put("expiresAt", expiresAt.toString());
        }
        if (seats != null) {
            node.put("seats", seats);
        }
        if (!modules.isEmpty()) {
            ArrayNode array = node.putArray("modules");
            modules.forEach(array::add);
        }
        if (graceDays != null) {
            node.put("graceDays", graceDays);
        }
        if (kid != null) {
            node.put("kid", kid);
        }
        if (sig != null) {
            node.put("sig", sig);
        }
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n";
        } catch (Exception ex) {
            throw new LicenseFormatException("无法序列化 license JSON", ex);
        }
    }

    public static LicenseDocument parse(String json) {
        if (json == null || json.isBlank()) {
            throw new LicenseFormatException("license 文件为空");
        }
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (Exception ex) {
            throw new LicenseFormatException("license 不是合法 JSON", ex);
        }
        if (node == null || !node.isObject()) {
            throw new LicenseFormatException("license 根节点必须是 JSON 对象");
        }
        return new LicenseDocument(
                node.path("v").asInt(0),
                text(node, "subject"),
                date(node, "issuedAt", true),
                date(node, "expiresAt", false),
                integer(node, "seats"),
                stringList(node.path("modules")),
                integer(node, "graceDays"),
                text(node, "kid"),
                text(node, "sig"));
    }

    public static LicenseDocument unsigned(
            String subject,
            LocalDate issuedAt,
            LocalDate expiresAt,
            Integer seats,
            List<String> modules,
            Integer graceDays,
            String kid) {
        return new LicenseDocument(VERSION, subject, issuedAt, expiresAt, seats, modules, graceDays, kid, null);
    }

    private static void appendLine(StringBuilder text, String line) {
        text.append(line).append('\n');
    }

    private static List<String> normalizeModules(List<String> modules) {
        if (modules == null || modules.isEmpty()) {
            return List.of();
        }
        TreeSet<String> unique = new TreeSet<>();
        for (String module : modules) {
            if (module == null || module.isBlank()) {
                continue;
            }
            unique.add(module.strip());
        }
        return List.copyOf(unique);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() && !value.isNumber()) {
            throw new LicenseFormatException("字段 " + field + " 必须是字符串");
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static LocalDate date(JsonNode node, String field, boolean required) {
        String text = text(node, field);
        if (text == null) {
            if (required) {
                throw new LicenseFormatException("license 缺少 " + field);
            }
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException ex) {
            throw new LicenseFormatException(field + " 必须是 YYYY-MM-DD", ex);
        }
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
            return null;
        }
        if (!value.isIntegralNumber() && !value.isTextual()) {
            throw new LicenseFormatException("字段 " + field + " 必须是整数");
        }
        try {
            return Integer.valueOf(value.asText().strip());
        } catch (NumberFormatException ex) {
            throw new LicenseFormatException("字段 " + field + " 必须是整数", ex);
        }
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new LicenseFormatException("modules 必须是字符串数组");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || item.isNull()) {
                continue;
            }
            if (!item.isTextual()) {
                throw new LicenseFormatException("modules 元素必须是字符串");
            }
            values.add(item.asText());
        }
        return values;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
