package com.sjherp.app.memory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.domain.gap.GapRecord;
import com.sjherp.domain.gap.GapRecordService;
import com.sjherp.domain.gap.GapRecordNotFoundException;
import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemoryType;
import com.sjherp.domain.memory.MemoryWriteSource;
import com.sjherp.domain.memory.StructuredMemoryCandidate;

/** 将用户已确认的结构化知识写入 T01 大记忆真源。 */
public class WriteMemoryTool implements Tool {
    public static final String NAME = "write_memory";

    private final MemoryWriteChannel channel;
    private final GapRecordService gapService;

    public WriteMemoryTool(MemoryWriteChannel channel, GapRecordService gapService) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.gapService = Objects.requireNonNull(gapService, "gapService");
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "写入经用户确认的结构化业务记忆，包括缺口解决方案、业务术语/口径和操作偏好。"
                + "系统会展示高风险确认卡片，确认后才写入；禁止用于聊天召回。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{
                "type":{"type":"string","enum":["GAP_SOLUTION","BUSINESS_TERM","METRIC_DEFINITION","OPERATION_PREFERENCE"]},
                "title":{"type":"string","description":"记忆标题，200 字以内"},
                "facts":{"type":"object","description":"结构化事实，值使用字符串；金额和数量必须使用十进制字符串"},
                "source_kind":{"type":"string","enum":["USER_INPUT","GAP_RECORD"]},
                "gap_record_id":{"type":"integer","description":"source_kind=GAP_RECORD 时必填的缺口记录 id"}},
                "required":["type","title","facts","source_kind"],"additionalProperties":false} """;
    }

    @Override
    public ToolRiskLevel riskLevel() { return ToolRiskLevel.HIGH; }

    @Override
    public String requiredPermission() { return "memory:manage"; }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        try {
            String sourceKind = text(arguments.get("source_kind")).toUpperCase(Locale.ROOT);
            MemoryWriteSource source;
            String sourceRef;
            switch (sourceKind) {
                case "GAP_RECORD" -> {
                    GapRecord gap = gapService.get(longValue(arguments.get("gap_record_id")));
                    source = MemoryWriteSource.GAP_RECORD;
                    sourceRef = gap.getGapNo();
                }
                case "USER_INPUT" -> {
                    source = MemoryWriteSource.AGENT_SESSION;
                    sourceRef = context.sessionId();
                }
                default -> throw new IllegalArgumentException(
                        "source_kind 仅支持 USER_INPUT 或 GAP_RECORD");
            }
            StructuredMemoryCandidate candidate = new StructuredMemoryCandidate(
                    MemoryType.valueOf(text(arguments.get("type")).toUpperCase(Locale.ROOT)),
                    text(arguments.get("title")), facts(arguments.get("facts")), source,
                    sourceRef, context.sessionId(), true);
            MemoryEntry entry = channel.approveAndWrite(candidate, operator(context));
            return ToolResult.ok(Map.of("memoryNo", entry.getMemoryNo(), "version", entry.getVersion(),
                    "sourceRef", sourceRef));
        } catch (IllegalArgumentException | IllegalStateException | GapRecordNotFoundException exception) {
            return ToolResult.fail("记忆写入被拒绝: " + exception.getMessage());
        }
    }

    private static Map<String, String> facts(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("facts 必须是对象");
        }
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (!(item instanceof String stringValue)) {
                throw new IllegalArgumentException("facts 的值必须是字符串");
            }
            result.put(text(key), stringValue);
        });
        return result;
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(text(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("gap_record_id 必须是整数");
        }
    }

    private static String operator(ToolContext context) {
        String userId = context.userId();
        return "agent:" + (userId == null || userId.isBlank() ? "anonymous" : userId);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
