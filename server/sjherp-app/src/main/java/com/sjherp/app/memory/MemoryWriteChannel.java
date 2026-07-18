package com.sjherp.app.memory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemoryEntryCommand;
import com.sjherp.domain.memory.MemorySourceType;
import com.sjherp.domain.memory.StructuredMemoryCandidate;

/** M6-T02 写入通道：先结构化候选，再由明确的批准动作提交 T01 真源服务。 */
public class MemoryWriteChannel {
    private final MemoryService memoryService;

    public MemoryWriteChannel(MemoryService memoryService) {
        this.memoryService = Objects.requireNonNull(memoryService, "memoryService");
    }

    public StructuredMemoryCandidate propose(StructuredMemoryCandidate candidate) {
        return Objects.requireNonNull(candidate, "candidate");
    }

    @Transactional
    @Audited(action = "memory.write_from_candidate", targetType = "memory")
    public MemoryEntry approveAndWrite(StructuredMemoryCandidate candidate, String approver) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.requiresHumanApproval() && (approver == null || approver.isBlank())) {
            throw new IllegalStateException("该记忆候选必须经过人工确认");
        }
        String operator = approver == null || approver.isBlank() ? "system:memory-writer" : approver;
        String content = canonicalContent(candidate.facts());
        MemorySourceType sourceType = switch (candidate.source()) {
            case GAP_RECORD -> MemorySourceType.GAP_RECORD;
            case AGENT_SESSION, USER_INPUT -> MemorySourceType.USER_INPUT;
            case BUSINESS_DOCUMENT -> MemorySourceType.BUSINESS_DOC;
        };
        String sourceRef = candidate.sourceRef();
        String memoryKey = "write:" + UUID.nameUUIDFromBytes((candidate.memoryType().name() + "\n"
                + candidate.title() + "\n" + sourceType.name() + "\n" + sourceRef + "\n" + content)
                .getBytes(StandardCharsets.UTF_8));
        return memoryService.createIdempotent(memoryKey,
                new MemoryEntryCommand(candidate.memoryType(), candidate.title(), content,
                        sourceType, sourceRef, null, null), operator);
    }

    static String canonicalContent(Map<String, String> facts) {
        return new TreeMap<>(facts).entrySet().stream()
                .map(e -> quote(e.getKey()) + ":" + quote(e.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }
}
