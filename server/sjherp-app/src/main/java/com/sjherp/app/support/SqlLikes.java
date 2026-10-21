package com.sjherp.app.support;

/**
 * JDBC {@code LIKE} 关键字转义，避免用户输入 {@code %} / {@code _} 放大匹配范围（不是 SQL 注入：
 * 参数仍走 {@code ?} 绑定）。口径对齐 infra 仓储的 escapeLike。
 */
public final class SqlLikes {

    private SqlLikes() {
    }

    /** 包裹为包含匹配，并对 {@code %} {@code _} {@code \} 转义。 */
    public static String contains(String keyword) {
        if (keyword == null) {
            return "%";
        }
        return "%" + escape(keyword.strip()) + "%";
    }

    public static String escape(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
