package com.sjherp.app.tool;

import java.util.Locale;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.partner.SettlementMethod;

/**
 * 档案类 Agent 工具公共助手（M2-T08）。
 *
 * <p>统一约定：
 * <ul>
 *   <li>查询类工具最多返回 {@link #MAX_ITEMS} 条精简列表（避免把整页档案灌进上下文）；</li>
 *   <li>写操作的审计操作人一律记 {@code agent:<userId>}（CLAUDE.md 原则 3：
 *       区分人工与 Agent 操作，最终责任人是会话所属用户）；</li>
 *   <li>面向用户的状态/结算方式输出中文标签，参数输入用英文枚举值（schema enum 约束）。</li>
 * </ul>
 */
public final class ArchiveToolSupport {

    /** 查询类工具单次返回条数上限 */
    public static final int MAX_ITEMS = 10;

    private ArchiveToolSupport() {
    }

    /** 审计操作人标识：agent:<userId>（userId 为会话所属 sys_user.id 字符串） */
    public static String operator(ToolContext context) {
        String userId = context == null ? null : context.userId();
        return "agent:" + ((userId == null || userId.isBlank()) ? "anonymous" : userId);
    }

    /** 参数值 → 字符串（null 安全；空白收敛为 null，与领域层"空白视为未填"语义一致） */
    public static String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text.strip();
    }

    /** 参数值 → 档案状态过滤条件（null/空白 = 不过滤；非法值由框架 enum 校验兜底，此处再防御一次） */
    public static ArchiveStatus parseStatus(Object value) {
        String text = str(value);
        return text == null ? null : ArchiveStatus.valueOf(text.toUpperCase(Locale.ROOT));
    }

    /** 档案状态 → 中文标签 */
    public static String statusLabel(ArchiveStatus status) {
        return status == ArchiveStatus.ENABLED ? "启用" : "停用";
    }

    /** 参数值 → 结算方式（null/空白 = 默认月结 MONTHLY） */
    public static SettlementMethod parseSettlementOrDefault(Object value) {
        String text = str(value);
        return text == null ? SettlementMethod.MONTHLY
                : SettlementMethod.valueOf(text.toUpperCase(Locale.ROOT));
    }

    /** 结算方式 → 中文标签 */
    public static String settlementLabel(SettlementMethod method) {
        return switch (method) {
            case MONTHLY -> "月结";
            case CASH -> "现结";
            case PREPAID -> "预付";
        };
    }
}
