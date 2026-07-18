# M6-T02 Memory Write Channel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将缺口解决方案、业务术语/口径和操作偏好以结构化、可溯源、需确认且幂等的方式写入 T01 `memory_entry` 真源。

**Architecture:** 领域层只定义不可变结构化候选和来源约束；应用层将候选规范化为确定性 JSON，生成稳定 `memoryKey`，再调用 T01 `MemoryService`。Agent 仅新增一个受 `memory:manage` 保护的 HIGH 工具，继续使用既有持久化 HITL、审计和索引事件链。

**Tech Stack:** Java 21、Spring Boot 3.4、JUnit 5、Mockito、AssertJ、MySQL 8.4、Qdrant 1.13.4。

## Global Constraints

- 不新增依赖、数据表或迁移，不修改 T01 的 MySQL 真源与 Qdrant 派生索引模型。
- 不实现 M6-T03 对话召回/提示注入，不实现 M6-T04 管理前端。
- 所有业务写入必须经领域/应用服务；禁止物理删除；写入必须审计。
- 金额、数量事实只接受十进制字符串，禁止经 `double` 转换。
- Java 与 Maven 命令使用 Temurin 21.0.11。

---

### Task 1: Structured Candidate Contract

**Files:**
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/memory/MemoryWriteSource.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/memory/StructuredMemoryCandidate.java`
- Test: `server/sjherp-domain/src/test/java/com/sjherp/domain/memory/StructuredMemoryCandidateTest.java`

**Interfaces:**
- Consumes: T01 `MemoryType`。
- Produces: `StructuredMemoryCandidate(MemoryType, String, Map<String,String>, MemoryWriteSource, String, String, boolean)`。

- [x] **Step 1: Write failing validation tests**

```java
assertThatThrownBy(() -> candidate(Map.of(" ", "500000")))
        .isInstanceOf(IllegalArgumentException.class);
assertThatThrownBy(() -> new StructuredMemoryCandidate(MemoryType.GAP_SOLUTION,
        "解决方案", Map.of("solution", "增加月结导出"), MemoryWriteSource.AGENT_SESSION,
        "session-1", "session-1", true)).isInstanceOf(IllegalArgumentException.class);
```

- [x] **Step 2: Verify RED**

Run: `mvn -pl sjherp-domain -am -Dtest=StructuredMemoryCandidateTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because the candidate/source contract does not exist or does not reject invalid input.

- [x] **Step 3: Implement the immutable contract**

```java
public record StructuredMemoryCandidate(MemoryType memoryType, String title,
        Map<String, String> facts, MemoryWriteSource source, String sourceRef,
        String sessionId, boolean requiresHumanApproval) {
    public StructuredMemoryCandidate {
        Objects.requireNonNull(memoryType, "memoryType");
        Objects.requireNonNull(source, "source");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title 不能为空");
        if (facts == null || facts.isEmpty() || facts.size() > 50)
            throw new IllegalArgumentException("facts 数量非法");
        if (sourceRef == null || sourceRef.isBlank())
            throw new IllegalArgumentException("sourceRef 不能为空");
        if (memoryType == MemoryType.GAP_SOLUTION && source != MemoryWriteSource.GAP_RECORD)
            throw new IllegalArgumentException("缺口解决方案必须追溯到 GapRecord");
    }
}
```

- [x] **Step 4: Verify GREEN**

Run the Step 2 command. Expected: `StructuredMemoryCandidateTest` passes with zero failures.

### Task 2: Approved and Idempotent Application Write

**Files:**
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryWriteChannel.java`
- Modify: `server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryService.java`
- Test: `server/sjherp-app/src/test/java/com/sjherp/app/memory/MemoryWriteChannelTest.java`
- Test: `server/sjherp-app/src/test/java/com/sjherp/app/memory/MemoryServiceTest.java`

**Interfaces:**
- Consumes: `StructuredMemoryCandidate` and T01 `MemoryEntryCommand`。
- Produces: `MemoryWriteChannel.approveAndWrite(...)` and `MemoryService.createIdempotent(...)`。

- [x] **Step 1: Write failing canonicalization, HITL, and replay tests**

```java
assertThat(MemoryWriteChannel.canonicalContent(Map.of("threshold", "500000", "unit", "CNY")))
        .isEqualTo("{\"threshold\":\"500000\",\"unit\":\"CNY\"}");
assertThatThrownBy(() -> channel.approveAndWrite(candidate, " "))
        .isInstanceOf(IllegalStateException.class);
assertThat(service.createIdempotent("write:key", command, "agent:1")).isSameAs(existing);
```

- [x] **Step 2: Verify RED**

Run: `mvn -pl sjherp-app -am -Dtest=MemoryWriteChannelTest,MemoryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL before the channel and idempotent service method exist.

- [x] **Step 3: Implement deterministic approved write**

```java
@Transactional
@Audited(action = "memory.write_from_candidate", targetType = "memory")
public MemoryEntry approveAndWrite(StructuredMemoryCandidate candidate, String approver) {
    if (candidate.requiresHumanApproval() && (approver == null || approver.isBlank()))
        throw new IllegalStateException("该记忆候选必须经过人工确认");
    String content = canonicalContent(candidate.facts());
    MemorySourceType sourceType = switch (candidate.source()) {
        case GAP_RECORD -> MemorySourceType.GAP_RECORD;
        case AGENT_SESSION, USER_INPUT -> MemorySourceType.USER_INPUT;
        case BUSINESS_DOCUMENT -> MemorySourceType.BUSINESS_DOC;
    };
    String memoryKey = "write:" + UUID.nameUUIDFromBytes((candidate.memoryType().name()
            + "\n" + candidate.title() + "\n" + sourceType.name() + "\n"
            + candidate.sourceRef() + "\n" + content).getBytes(StandardCharsets.UTF_8));
    return memoryService.createIdempotent(memoryKey,
            new MemoryEntryCommand(candidate.memoryType(), candidate.title(), content,
                    sourceType, candidate.sourceRef(), null, null), approver);
}
```

`createIdempotent` 先调用 `findActiveByMemoryKey`；命中相同内容时返回已有记录，命中不同内容时抛出“幂等键冲突”，未命中时复用 T01 创建和索引事件路径。

- [x] **Step 4: Verify GREEN**

Run the Step 2 command. Expected: all channel and service tests pass with zero failures.

### Task 3: Agent Tool, Permission, and Wiring

**Files:**
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/memory/WriteMemoryTool.java`
- Modify: `server/sjherp-app/src/main/java/com/sjherp/app/config/MemoryInfraConfig.java`
- Modify: `server/sjherp-app/src/main/java/com/sjherp/app/chat/LlmAgent.java`
- Test: `server/sjherp-app/src/test/java/com/sjherp/app/memory/WriteMemoryToolTest.java`
- Test: `server/sjherp-app/src/test/java/com/sjherp/app/config/MemoryInfraConfigTest.java`

**Interfaces:**
- Consumes: `MemoryWriteChannel`, `GapRecordService`, `ToolContext` and existing `ToolRegistry`。
- Produces: HIGH tool `write_memory`, required permission `memory:manage`。

- [x] **Step 1: Write failing tool boundary tests**

```java
assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
assertThat(tool.requiredPermission()).isEqualTo("memory:manage");
verify(gapService).get(7L);
assertThat(candidate.sourceRef()).isEqualTo("GAP-202607-0001");
```

- [x] **Step 2: Verify RED**

Run: `mvn -pl sjherp-app -am -Dtest=WriteMemoryToolTest,MemoryInfraConfigTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL before `write_memory` and its conditional registration exist.

- [x] **Step 3: Implement strict source and fact handling**

```java
@Override public ToolRiskLevel riskLevel() { return ToolRiskLevel.HIGH; }
@Override public String requiredPermission() { return "memory:manage"; }

case "GAP_RECORD" -> sourceRef = gapService.get(
        longValue(arguments.get("gap_record_id"))).getGapNo();
case "USER_INPUT" -> sourceRef = context.sessionId();
default -> throw new IllegalArgumentException(
        "source_kind 仅支持 USER_INPUT 或 GAP_RECORD");
```

Every `facts` value must be a `String`; numeric Java values are rejected before calling the channel. Register the tool only inside the existing `sjherp.memory.enabled=true` configuration.

- [x] **Step 4: Verify GREEN**

Run the Step 2 command. Expected: tool and configuration tests pass with zero failures.

### Task 4: Documentation and Verification Gates

**Files:**
- Create: `docs/superpowers/specs/2026-07-18-m6-memory-write-channel-design.md`
- Modify: `docs/业务-系统大记忆.md`
- Modify: `docs/产品路线图-0到1.md`
- Modify: `docs/领域工具清单.md`
- Modify: `CLAUDE.md`
- Test: `server/sjherp-app/src/test/java/com/sjherp/app/memory/MemoryFoundationIntegrationTest.java`

**Interfaces:**
- Consumes: completed Tasks 1-3.
- Produces: route-map completion record and MySQL replay acceptance test.

- [x] **Step 1: Add true-database replay acceptance**

```java
MemoryEntry first = memoryService.createIdempotent(memoryKey, command, "agent:1");
MemoryEntry replay = memoryService.createIdempotent(memoryKey, command, "agent:1");
assertThat(replay.getId()).isEqualTo(first.getId());
assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_entry", Integer.class)).isEqualTo(1);
```

- [x] **Step 2: Run focused verification**

Run: `mvn -pl sjherp-app -am -Dtest=*Memory*Test,WriteMemoryToolTest,StructuredMemoryCandidateTest,AuditWriteCoverageTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: 111 focused tests pass (19 domain + 92 application), zero failures/errors.

- [x] **Step 3: Run full verification**

Run: `mvn test`

Expected: 1263 tests pass, zero failures/errors. The `integration-db` profile runs `MemoryFoundationIntegrationTest` against MySQL 8.4 and Qdrant 1.13.4 in CI.

- [x] **Step 4: Verify repository hygiene**

Run: `git diff --check`

Expected: exit code 0; no whitespace errors, no migration/POM/frontend files, and no T03 recall implementation.
