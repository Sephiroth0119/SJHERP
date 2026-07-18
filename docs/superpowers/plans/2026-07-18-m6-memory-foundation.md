# M6-T01 大记忆基建 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立以 MySQL 为唯一真源、Ollama 本地生成 1024 维向量、Qdrant 作为可重建派生索引的大记忆底座。

**Architecture:** 领域层定义记忆聚合与 Embedding/VectorIndex 端口，基础设施层用 JDK `HttpClient` 实现 Ollama 与 Qdrant HTTP 客户端，应用层用“真源事务 + 提交后索引 + 定时重试”实现最终一致性。功能默认关闭；启用时严格验证 Qdrant collection 的 1024 维 Cosine 规范，所有管理写操作经统一权限与审计入口。

**Tech Stack:** Java 21、Spring Boot 3.4.5、Maven、多模块架构、MySQL 8.4、Flyway、Ollama `/api/embed`、`qwen3-embedding:0.6b`、Qdrant v1.13.4 HTTP API、JUnit 5、Mockito、Testcontainers。

## Global Constraints

- 设计真源：`docs/superpowers/specs/2026-07-18-m6-memory-foundation-design.md`。
- 业务文本只发送到本地 Ollama，禁止增加任何外部 Embedding 回退路径。
- MySQL 是唯一真源；Qdrant payload 禁止包含标题、原文、用户原话、业务正文或密钥。
- 默认 `sjherp.memory.enabled=false`，不得影响既有 ERP 与 Agent 启动。
- 模型固定 `qwen3-embedding:0.6b`，向量维度固定 1024，距离固定 Cosine。
- 不引入 Spring AI、Ollama SDK、Qdrant gRPC SDK、ONNX Runtime 或其他重量级框架。
- 所有业务写操作必须经过服务层并可审计；不提供物理删除。
- 数据库迁移必须接在存货分类 `V31` 之后，使用 `V32__memory_foundation.sql`。执行前若主线尚未包含提交 `ff6bab8`，先完成前序功能分支集成，不得复用 V31。
- 代码标识符使用英文；注释、文档、提交信息、用户文案使用中文。
- 金额规则不适用于本模块，但数据库时间统一 `DATETIME(6)`、Java 时间统一 `Instant`。
- 所有 Maven 命令的工作目录固定为 `D:\Code\SJHERP\server`；所有 Git 命令的工作目录固定为仓库根或实施 worktree 根。

## File Structure

### Domain

- `server/sjherp-domain/src/main/java/com/sjherp/domain/memory/MemoryEntry.java`：记忆聚合、版本/失效/索引状态不变式、审计摘要。
- `MemoryType.java`、`MemorySourceType.java`、`MemoryStatus.java`、`MemoryIndexStatus.java`：受控枚举。
- `MemoryEntryCommand.java`、`MemoryEntryQuery.java`、`MemoryEntryRepository.java`、`MemoryEntryNotFoundException.java`：领域命令、查询和仓储端口。
- `EmbeddingPurpose.java`、`EmbeddingVector.java`、`EmbeddingClient.java`：Embedding 端口。
- `VectorCollectionSpec.java`、`VectorPoint.java`、`VectorQuery.java`、`VectorMatch.java`、`VectorIndex.java`：Qdrant 无关的向量端口。

### Infrastructure

- `server/sjherp-infra/src/main/resources/db/migration/V32__memory_foundation.sql`：`memory_entry` 表与索引。
- `server/sjherp-infra/src/main/java/com/sjherp/infra/persistence/memory/JdbcMemoryEntryRepository.java`：MySQL 实现。
- `server/sjherp-infra/src/main/java/com/sjherp/infra/memory/OllamaEmbeddingClient.java`：Ollama 原生 HTTP 客户端。
- `OllamaEmbeddingException.java`：Ollama 错误上下文。
- `QdrantVectorIndex.java`、`QdrantVectorException.java`：Qdrant HTTP 客户端及异常。

### Application

- `server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryProperties.java`：`sjherp.memory.*` 配置与 fail-fast 校验。
- `MemoryService.java`：真源创建、替代、失效、查询。
- `MemoryIndexingService.java`：单条索引、失败记录、手工重试和重建编排。
- `MemoryIndexStateService.java`：索引状态的短事务写入。
- `MemoryIndexRequestedEvent.java`、`MemoryIndexEventListener.java`：事务提交后快速索引。
- `MemoryIndexRetryJob.java`：定时批量重试。
- `MemoryDtos.java`、`MemoryController.java`、`MemoryExceptionHandler.java`：管理 API。
- `server/sjherp-app/src/main/java/com/sjherp/app/config/MemoryInfraConfig.java`：条件装配。

### Existing files to modify

- `server/sjherp-domain/src/main/java/com/sjherp/domain/identity/Permission.java`
- `server/sjherp-domain/src/main/java/com/sjherp/domain/identity/RolePermissions.java`
- `server/sjherp-domain/src/test/java/com/sjherp/domain/identity/RolePermissionsTest.java`
- `server/sjherp-domain/src/test/java/com/sjherp/domain/common/NoPhysicalDeleteArchitectureTest.java`
- `server/sjherp-app/src/main/resources/application.yml`
- `server/sjherp-app/src/test/java/com/sjherp/app/audit/AuditWriteCoverageTest.java`
- `docs/权限矩阵.md`
- `docs/产品路线图-0到1.md`
- `CLAUDE.md`

---

### Task 1: 领域模型与本地端口

**Files:**
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/memory/MemoryEntry.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/memory/MemoryType.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/memory/MemorySourceType.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/memory/MemoryStatus.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/memory/MemoryIndexStatus.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/memory/MemoryEntryCommand.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/memory/MemoryEntryQuery.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/memory/MemoryEntryRepository.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/memory/MemoryEntryNotFoundException.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/memory/EmbeddingPurpose.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/memory/EmbeddingVector.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/memory/EmbeddingClient.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/memory/VectorCollectionSpec.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/memory/VectorPoint.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/memory/VectorQuery.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/memory/VectorMatch.java`
- Create: `server/sjherp-domain/src/main/java/com/sjherp/domain/memory/VectorIndex.java`
- Create: `server/sjherp-domain/src/test/java/com/sjherp/domain/memory/MemoryEntryTest.java`
- Create: `server/sjherp-domain/src/test/java/com/sjherp/domain/memory/MemoryVectorContractsTest.java`
- Modify: `server/sjherp-domain/src/test/java/com/sjherp/domain/common/NoPhysicalDeleteArchitectureTest.java`

**Interfaces:**
- Consumes: `AuditTarget`, `PageResult` 和项目既有 `Instant`/分页范式。
- Produces: `MemoryEntryRepository`、`EmbeddingClient`、`VectorIndex`，供 Tasks 2–5 实现与编排。

- [ ] **Step 1: 写聚合失败测试**

```java
class MemoryEntryTest {
    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

    @Test
    void 新建记忆初始为活动且待索引() {
        MemoryEntry entry = MemoryEntry.create("MEM-202607-0001", "MEM-202607-0001", 1,
                MemoryType.BUSINESS_TERM, "大客户口径", "年采购金额超过50万元",
                MemorySourceType.USER_INPUT, "session-1", NOW, null, "user:1", NOW);
        assertThat(entry.getStatus()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(entry.getIndexStatus()).isEqualTo(MemoryIndexStatus.PENDING);
        assertThat(entry.getContentHash()).hasSize(64);
    }

    @Test
    void 旧版本被替代后不可恢复为活动() {
        MemoryEntry entry = fixture();
        entry.markSuperseded("user:1", NOW.plusSeconds(1));
        assertThatThrownBy(() -> entry.markPending("user:1", NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 索引成功必须记录模型维度与集合() {
        MemoryEntry entry = fixture();
        entry.markIndexed("sjherp-memory-qwen3-0_6b-1024-v1",
                "qwen3-embedding:0.6b", 1024, "system:memory-indexer", NOW.plusSeconds(1));
        assertThat(entry.getIndexStatus()).isEqualTo(MemoryIndexStatus.INDEXED);
        assertThat(entry.getEmbeddingDimension()).isEqualTo(1024);
    }
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -pl sjherp-domain -Dtest=MemoryEntryTest test`

Expected: FAIL，提示 `com.sjherp.domain.memory` 类型不存在。

- [ ] **Step 3: 实现枚举、命令和聚合**

必须使用以下枚举和值：

```java
public enum MemoryType { GAP_SOLUTION, BUSINESS_TERM, METRIC_DEFINITION, OPERATION_PREFERENCE }
public enum MemorySourceType { GAP_RECORD, USER_INPUT, BUSINESS_DOC, SYSTEM }
public enum MemoryStatus { ACTIVE, SUPERSEDED, EXPIRED, CONFLICT }
public enum MemoryIndexStatus { PENDING, INDEXED, FAILED }
public enum EmbeddingPurpose { DOCUMENT, QUERY }

public record MemoryEntryCommand(MemoryType memoryType, String title, String content,
        MemorySourceType sourceType, String sourceRef, Instant validFrom, Instant validTo) {}

public record MemoryEntryQuery(MemoryType memoryType, MemoryStatus status,
        MemoryIndexStatus indexStatus, int page, int size) {}
```

聚合公开写方法固定为：

```java
public static MemoryEntry create(String memoryNo, String memoryKey, int version,
        MemoryType memoryType, String title, String content,
        MemorySourceType sourceType, String sourceRef,
        Instant validFrom, Instant validTo, String operator, Instant now);

public static MemoryEntry restore(long id, long tenantId, String memoryNo, String memoryKey,
        int version, Long previousId, MemoryType memoryType, String title, String content,
        String contentHash, MemorySourceType sourceType, String sourceRef, MemoryStatus status,
        Instant validFrom, Instant validTo, MemoryIndexStatus indexStatus,
        String indexedCollection, String embeddingModel, Integer embeddingDimension,
        int retryCount, Instant nextRetryAt, String lastIndexError,
        String createdBy, Instant createdAt, String updatedBy, Instant updatedAt);

public void assignId(long id);
public void markSuperseded(String operator, Instant now);
public void expire(String operator, Instant now);
public void markPending(String operator, Instant now);
public void markIndexed(String collection, String model, int dimension, String operator, Instant now);
public void markIndexFailed(String error, Instant nextRetryAt, String operator, Instant now);
```

实现规则：`create` 规范化首尾空白后计算 SHA-256；`version >= 1`；标题最多 200、原文非空、来源编号最多 128；失效或被替代后禁止回到 `PENDING`；`markIndexFailed` 每次将 `retryCount+1`；错误摘要最多 1000 字符且不拼入原文。

`MemoryEntryTest.fixture()` 必须返回以下确定对象，禁止测试依赖当前时间：

```java
private static MemoryEntry fixture() {
    return MemoryEntry.create("MEM-202607-0001", "MEM-202607-0001", 1,
            MemoryType.BUSINESS_TERM, "大客户口径", "年采购金额超过50万元",
            MemorySourceType.USER_INPUT, "session-1", NOW, null, "user:1", NOW);
}
```

- [ ] **Step 4: 写端口契约失败测试**

```java
class MemoryVectorContractsTest {
    @Test
    void 向量维度必须与元素数量一致且数值有限() {
        assertThatThrownBy(() -> new EmbeddingVector("model", 2,
                List.of(1.0f, Float.NaN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingVector("model", 2, List.of(1.0f)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void collection规范拒绝非正维度() {
        assertThatThrownBy(() -> new VectorCollectionSpec("memory-v1", 0, "COSINE"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 5: 实现端口记录类型**

```java
public record EmbeddingVector(String model, int dimension, List<Float> values) {}
public interface EmbeddingClient { EmbeddingVector embed(String text, EmbeddingPurpose purpose); }
public record VectorCollectionSpec(String name, int dimension, String distance) {}
public record VectorPoint(long memoryEntryId, long tenantId, MemoryType memoryType,
        MemoryStatus memoryStatus, MemorySourceType sourceType, List<Float> vector) {}
public record VectorQuery(List<Float> vector, long tenantId, Set<MemoryType> memoryTypes,
        int limit, Double minScore) {}
public record VectorMatch(long memoryEntryId, double score) {}
public interface VectorIndex {
    void ensureCollection(VectorCollectionSpec spec);
    void upsert(VectorPoint point);
    void delete(long memoryEntryId);
    List<VectorMatch> search(VectorQuery query);
}
```

所有 record 紧凑构造器复制集合为不可变副本，并执行 null、空值、维度、有限数和 `limit` 范围校验。

- [ ] **Step 6: 定义仓储接口并加入防删守卫**

```java
public interface MemoryEntryRepository {
    void save(MemoryEntry entry);
    Optional<MemoryEntry> findByMemoryNo(String memoryNo);
    Optional<MemoryEntry> findActiveByMemoryKey(String memoryKey);
    PageResult<MemoryEntry> search(MemoryEntryQuery query);
    List<MemoryEntry> findIndexCandidates(Instant dueAt, int limit);
    List<MemoryEntry> findActiveAfterId(long afterId, int limit);
}
```

将 `MemoryEntry` 与 `MemoryEntryRepository` 加入 `NoPhysicalDeleteArchitectureTest` 的聚合/服务/仓储扫描集合，继续禁止 `delete/remove` 业务方法。`VectorIndex.delete` 是删除派生索引，不属于业务真源物理删除，测试须按包边界排除该端口。

- [ ] **Step 7: 运行领域测试确认 GREEN**

Run: `mvn -pl sjherp-domain -Dtest=MemoryEntryTest,MemoryVectorContractsTest,NoPhysicalDeleteArchitectureTest test`

Expected: PASS，0 failures。

- [ ] **Step 8: 提交 Task 1**

```bash
git add server/sjherp-domain
git commit -m "feat: 建立大记忆领域模型与向量端口"
```

---

### Task 2: Flyway 迁移与 MySQL 仓储

**Files:**
- Create: `server/sjherp-infra/src/main/resources/db/migration/V32__memory_foundation.sql`
- Create: `server/sjherp-infra/src/main/java/com/sjherp/infra/persistence/memory/JdbcMemoryEntryRepository.java`
- Create: `server/sjherp-infra/src/test/java/com/sjherp/infra/persistence/memory/JdbcMemoryEntryRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 `MemoryEntryRepository` 与 `MemoryEntry.restore`。
- Produces: 可由 app 装配的 JDBC 仓储；Tasks 5、7 依赖分页与重试查询。

- [ ] **Step 1: 写迁移与仓储集成失败测试**

```java
@Tag("integration-db")
class JdbcMemoryEntryRepositoryIntegrationTest extends MySqlContainerTestBase {
    private final JdbcMemoryEntryRepository repository = new JdbcMemoryEntryRepository(jdbc);

    @Test
    void 保存回读并按索引到期时间分页() {
        MemoryEntry entry = pending("MEM-IT-" + uniqueSuffix(), NOW.minusSeconds(60));
        repository.save(entry);

        MemoryEntry restored = repository.findByMemoryNo(entry.getMemoryNo()).orElseThrow();
        assertThat(restored.getContent()).isEqualTo("年采购金额超过50万元");
        assertThat(repository.findIndexCandidates(NOW, 10))
                .extracting(MemoryEntry::getMemoryNo)
                .containsExactly(entry.getMemoryNo());
    }

    @Test
    void 同一逻辑键版本号不可重复() {
        repository.save(version("MEM-IT-A-" + uniqueSuffix(), "K-1", 1));
        assertThatThrownBy(() -> repository.save(version(
                "MEM-IT-B-" + uniqueSuffix(), "K-1", 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static MemoryEntry pending(String memoryNo, Instant nextRetryAt) {
        MemoryEntry entry = MemoryEntry.create(memoryNo, memoryNo, 1,
                MemoryType.BUSINESS_TERM, "大客户口径", "年采购金额超过50万元",
                MemorySourceType.USER_INPUT, "session-1", NOW, null, "tester", NOW);
        entry.markIndexFailed("暂时不可用", nextRetryAt, "system:memory-indexer", NOW);
        return entry;
    }

    private static MemoryEntry version(String memoryNo, String memoryKey, int version) {
        return MemoryEntry.create(memoryNo, memoryKey, version,
                MemoryType.BUSINESS_TERM, "口径", "正文",
                MemorySourceType.SYSTEM, "test", NOW, null, "tester", NOW);
    }
}
```

- [ ] **Step 2: 运行确认 RED**

Run: `mvn -pl sjherp-infra -am -Dgroups=integration-db -DexcludedGroups=none -Dtest=JdbcMemoryEntryRepositoryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，Flyway 尚无 `memory_entry` 表或仓储类型不存在。

- [ ] **Step 3: 创建 V32 迁移**

```sql
CREATE TABLE memory_entry
(
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id             BIGINT        NOT NULL DEFAULT 0,
    memory_no             VARCHAR(32)   NOT NULL,
    memory_key            VARCHAR(128)  NOT NULL,
    version               INT           NOT NULL,
    previous_id           BIGINT        NULL,
    memory_type           VARCHAR(32)   NOT NULL,
    title                 VARCHAR(200)  NOT NULL,
    content               LONGTEXT      NOT NULL,
    content_hash          CHAR(64)      NOT NULL,
    source_type           VARCHAR(32)   NOT NULL,
    source_ref            VARCHAR(128)  NOT NULL,
    status                VARCHAR(16)   NOT NULL,
    valid_from            DATETIME(6)   NOT NULL,
    valid_to              DATETIME(6)   NULL,
    index_status          VARCHAR(16)   NOT NULL,
    indexed_collection    VARCHAR(128)  NULL,
    embedding_model       VARCHAR(128)  NULL,
    embedding_dimension   INT           NULL,
    retry_count           INT           NOT NULL DEFAULT 0,
    next_retry_at         DATETIME(6)   NULL,
    last_index_error      VARCHAR(1000) NULL,
    created_by            VARCHAR(64)   NOT NULL,
    created_at            DATETIME(6)   NOT NULL,
    updated_by            VARCHAR(64)   NOT NULL,
    updated_at            DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_entry_no (tenant_id, memory_no),
    UNIQUE KEY uk_memory_entry_version (tenant_id, memory_key, version),
    KEY idx_memory_entry_retry (tenant_id, index_status, next_retry_at, id),
    KEY idx_memory_entry_governance (tenant_id, status, memory_type, id),
    KEY idx_memory_entry_source (tenant_id, source_type, source_ref),
    CONSTRAINT chk_memory_entry_version CHECK (version >= 1),
    CONSTRAINT chk_memory_entry_dimension CHECK (embedding_dimension IS NULL OR embedding_dimension > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '系统大记忆真源（M6-T01）';
```

- [ ] **Step 4: 实现 JDBC 映射与查询**

`JdbcMemoryEntryRepository` 使用一份 `SELECT_COLUMNS` 和一份 `RowMapper<MemoryEntry>`。写入约束如下：

```java
private static final String SELECT_COLUMNS = """
        SELECT id, tenant_id, memory_no, memory_key, version, previous_id,
               memory_type, title, content, content_hash, source_type, source_ref,
               status, valid_from, valid_to, index_status, indexed_collection,
               embedding_model, embedding_dimension, retry_count, next_retry_at,
               last_index_error, created_by, created_at, updated_by, updated_at
          FROM memory_entry
        """;

@Override
public List<MemoryEntry> findIndexCandidates(Instant dueAt, int limit) {
    return jdbc.query(SELECT_COLUMNS + """
            WHERE tenant_id = 0
              AND status = 'ACTIVE'
              AND (index_status = 'PENDING'
                   OR (index_status = 'FAILED' AND next_retry_at IS NOT NULL
                       AND next_retry_at <= ?))
            ORDER BY id
            LIMIT ?
            """, ROW_MAPPER, Timestamp.from(dueAt), limit);
}

@Override
public List<MemoryEntry> findActiveAfterId(long afterId, int limit) {
    return jdbc.query(SELECT_COLUMNS + """
            WHERE tenant_id = 0 AND status = 'ACTIVE' AND id > ?
            ORDER BY id LIMIT ?
            """, ROW_MAPPER, afterId, limit);
}
```

`save` 新记录用 `GeneratedKeyHolder` 回填 id；已有 id 只更新可变治理/索引字段与审计字段，不更新原文、`memory_key`、版本、来源和创建审计字段。新版本必须插入新行。

- [ ] **Step 5: 运行仓储集成测试确认 GREEN**

Run: `mvn -pl sjherp-infra -am -Dgroups=integration-db -DexcludedGroups=none -Dtest=JdbcMemoryEntryRepositoryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS；Flyway 从 V1 到 V32 全量迁移成功。

- [ ] **Step 6: 提交 Task 2**

```bash
git add server/sjherp-infra/src/main/resources/db/migration/V32__memory_foundation.sql server/sjherp-infra/src/main/java/com/sjherp/infra/persistence/memory server/sjherp-infra/src/test/java/com/sjherp/infra/persistence/memory
git commit -m "feat: 持久化大记忆真源"
```

---

### Task 3: Ollama 与 Qdrant HTTP 客户端

**Files:**
- Create: `server/sjherp-infra/src/main/java/com/sjherp/infra/memory/OllamaEmbeddingClient.java`
- Create: `server/sjherp-infra/src/main/java/com/sjherp/infra/memory/OllamaEmbeddingException.java`
- Create: `server/sjherp-infra/src/main/java/com/sjherp/infra/memory/QdrantVectorIndex.java`
- Create: `server/sjherp-infra/src/main/java/com/sjherp/infra/memory/QdrantVectorException.java`
- Create: `server/sjherp-infra/src/test/java/com/sjherp/infra/memory/OllamaEmbeddingClientTest.java`
- Create: `server/sjherp-infra/src/test/java/com/sjherp/infra/memory/QdrantVectorIndexTest.java`

**Interfaces:**
- Consumes: Task 1 `EmbeddingClient`、`VectorIndex` 及 record 类型。
- Produces: 无 Spring 注解、可独立测试的本地 HTTP 实现，供 Task 4 装配。

- [ ] **Step 1: 写 Ollama 客户端失败测试**

使用 `com.sun.net.httpserver.HttpServer` 在随机端口返回固定 JSON：

```java
@Test
void 调用原生embed并返回严格1024维() {
    server.respond("/api/embed", 200, jsonWithVector(1024));
    OllamaEmbeddingClient client = new OllamaEmbeddingClient(
            server.baseUri(), "qwen3-embedding:0.6b", 1024, Duration.ofSeconds(2));

    EmbeddingVector vector = client.embed("大客户口径", EmbeddingPurpose.DOCUMENT);

    assertThat(vector.model()).isEqualTo("qwen3-embedding:0.6b");
    assertThat(vector.dimension()).isEqualTo(1024);
    assertThat(server.lastRequestBody()).contains("qwen3-embedding:0.6b", "大客户口径");
}

@Test
void 返回维度不符时拒绝() {
    server.respond("/api/embed", 200, jsonWithVector(3));
    assertThatThrownBy(() -> client.embed("文本", EmbeddingPurpose.DOCUMENT))
            .isInstanceOf(OllamaEmbeddingException.class)
            .hasMessageContaining("期望 1024");
}
```

- [ ] **Step 2: 运行 Ollama 测试确认 RED**

Run: `mvn -pl sjherp-infra -Dtest=OllamaEmbeddingClientTest test`

Expected: FAIL，客户端类型不存在。

- [ ] **Step 3: 实现 OllamaEmbeddingClient**

请求固定为：

```json
{
  "model": "qwen3-embedding:0.6b",
  "input": "大客户口径"
}
```

解析 `embeddings[0]`。构造器去除 base URL 尾斜杠，设置 10 秒连接超时及配置的整体超时；非 2xx、超时、网络异常、空数组、NaN/Infinity、长度不是 1024 都抛 `OllamaEmbeddingException`。异常响应体最多保留 500 字符，禁止把请求原文放入异常。

- [ ] **Step 4: 写 Qdrant 客户端失败测试**

```java
@Test
void collection不存在时创建1024维Cosine集合() {
    server.enqueue(404, "{\"status\":{\"error\":\"not found\"}}");
    server.enqueue(200, "{\"result\":true,\"status\":\"ok\"}");

    client.ensureCollection(new VectorCollectionSpec("memory-v1", 1024, "COSINE"));

    assertThat(server.requests().get(1).body())
            .contains("\"size\":1024", "\"distance\":\"Cosine\"");
}

@Test
void 已有集合维度不一致时拒绝() {
    server.enqueue(200, collectionInfo(768, "Cosine"));
    assertThatThrownBy(() -> client.ensureCollection(SPEC_1024))
            .isInstanceOf(QdrantVectorException.class)
            .hasMessageContaining("维度不一致");
}

@Test
void upsert的payload不含原文() {
    server.enqueue(200, "{\"result\":{\"status\":\"completed\"},\"status\":\"ok\"}");
    client.upsert(point());
    assertThat(server.lastRequestBody())
            .contains("memory_entry_id", "memory_type")
            .doesNotContain("title", "content");
}
```

- [ ] **Step 5: 实现 QdrantVectorIndex**

使用以下 HTTP 路径：

```text
GET    /collections/{collection}
PUT    /collections/{collection}
PUT    /collections/{collection}/points?wait=true
POST   /collections/{collection}/points/delete?wait=true
POST   /collections/{collection}/points/query
```

upsert payload 只能由代码显式构造：

```java
ObjectNode payload = mapper.createObjectNode();
payload.put("memory_entry_id", point.memoryEntryId());
payload.put("tenant_id", point.tenantId());
payload.put("memory_type", point.memoryType().name());
payload.put("memory_status", point.memoryStatus().name());
payload.put("source_type", point.sourceType().name());
```

禁止对 `MemoryEntry` 或任意 DTO 直接 Jackson 序列化。`search` 返回 point id 与 score；payload 只用于 Qdrant 过滤，不作为业务结果。

- [ ] **Step 6: 运行客户端测试确认 GREEN**

Run: `mvn -pl sjherp-infra -Dtest=OllamaEmbeddingClientTest,QdrantVectorIndexTest test`

Expected: PASS，覆盖 2xx/4xx/5xx、超时、响应解析、collection 校验、payload 白名单。

- [ ] **Step 7: 提交 Task 3**

```bash
git add server/sjherp-infra/src/main/java/com/sjherp/infra/memory server/sjherp-infra/src/test/java/com/sjherp/infra/memory
git commit -m "feat: 接入本地 Ollama 与 Qdrant"
```

---

### Task 4: 配置与条件装配

**Files:**
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryProperties.java`
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/config/MemoryInfraConfig.java`
- Create: `server/sjherp-app/src/test/java/com/sjherp/app/memory/MemoryPropertiesTest.java`
- Create: `server/sjherp-app/src/test/java/com/sjherp/app/config/MemoryInfraConfigTest.java`
- Modify: `server/sjherp-app/src/main/resources/application.yml`

**Interfaces:**
- Consumes: Tasks 2–3 的 JDBC/Ollama/Qdrant 实现。
- Produces: 仅在 `sjherp.memory.enabled=true` 时存在的基础设施 beans。

- [ ] **Step 1: 写配置失败测试**

```java
@Test
void 默认关闭时不要求本地服务配置() {
    MemoryProperties p = MemoryProperties.disabled();
    assertThat(p.enabled()).isFalse();
}

@Test
void 启用时拒绝非1024维或非Cosine() {
    assertThatThrownBy(() -> enabledProperties(768, "COSINE"))
            .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> enabledProperties(1024, "DOT"))
            .isInstanceOf(IllegalStateException.class);
}
```

- [ ] **Step 2: 运行确认 RED**

Run: `mvn -pl sjherp-app -am -Dtest=MemoryPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，`MemoryProperties` 不存在。

- [ ] **Step 3: 实现 MemoryProperties 与 application.yml**

```java
@ConfigurationProperties(prefix = "sjherp.memory")
public record MemoryProperties(boolean enabled, Embedding embedding,
        Vector vector, Indexing indexing) {
    public record Embedding(String provider, URI baseUrl, String model,
            int dimension, long timeoutSeconds) {}
    public record Vector(String provider, URI baseUrl, String collection,
            String distance) {}
    public record Indexing(long retryDelaySeconds, int batchSize, int maxRetries) {}
}
```

启用时严格要求 `provider=ollama/qdrant`、model 非空、dimension=1024、distance=COSINE、batchSize 1–500、maxRetries 1–100。关闭时允许本地服务未运行，但仍提供安全默认值。

在 `application.yml` 增加设计规格 §9 的完整配置，并用环境变量覆盖 `SJHERP_MEMORY_ENABLED`、`SJHERP_OLLAMA_URL`、`SJHERP_QDRANT_URL`。

- [ ] **Step 4: 写条件装配测试**

```java
class MemoryInfraConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MemoryInfraConfig.class);

    @Test
    void 关闭时没有任何记忆客户端bean() {
        runner.withPropertyValues("sjherp.memory.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(EmbeddingClient.class);
            assertThat(context).doesNotHaveBean(VectorIndex.class);
        });
    }
}
```

- [ ] **Step 5: 实现 MemoryInfraConfig**

```java
@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
@ConditionalOnProperty(prefix = "sjherp.memory", name = "enabled", havingValue = "true")
public class MemoryInfraConfig {
    @Bean
    MemoryEntryRepository memoryEntryRepository(JdbcTemplate jdbc) {
        return new JdbcMemoryEntryRepository(jdbc);
    }

    @Bean
    EmbeddingClient embeddingClient(MemoryProperties p) {
        return new OllamaEmbeddingClient(p.embedding().baseUrl(),
                p.embedding().model(), p.embedding().dimension(),
                Duration.ofSeconds(p.embedding().timeoutSeconds()));
    }

    @Bean
    VectorIndex vectorIndex(MemoryProperties p) {
        return new QdrantVectorIndex(p.vector().baseUrl(),
                p.vector().collection(), Duration.ofSeconds(30));
    }

    @Bean SmartInitializingSingleton memoryCollectionValidator(
            VectorIndex index, MemoryProperties p) {
        return () -> index.ensureCollection(new VectorCollectionSpec(
                p.vector().collection(), p.embedding().dimension(), p.vector().distance()));
    }
}
```

将省略处实现为构造 `JdbcMemoryEntryRepository`、`OllamaEmbeddingClient`、`QdrantVectorIndex`，不得增加降级为外部 Embedding 的分支。

- [ ] **Step 6: 运行配置测试确认 GREEN**

Run: `mvn -pl sjherp-app -am -Dtest=MemoryPropertiesTest,MemoryInfraConfigTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS；关闭时不连接本地服务，开启且 collection 不匹配时 context 启动失败。

- [ ] **Step 7: 提交 Task 4**

```bash
git add server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryProperties.java server/sjherp-app/src/main/java/com/sjherp/app/config/MemoryInfraConfig.java server/sjherp-app/src/main/resources/application.yml server/sjherp-app/src/test/java/com/sjherp/app/memory/MemoryPropertiesTest.java server/sjherp-app/src/test/java/com/sjherp/app/config/MemoryInfraConfigTest.java
git commit -m "feat: 配置大记忆本地基础设施"
```

---

### Task 5: 真源服务与最终一致性索引编排

**Files:**
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryService.java`
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryIndexingService.java`
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryIndexStateService.java`
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryIndexRequestedEvent.java`
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryIndexEventListener.java`
- Create: `server/sjherp-app/src/test/java/com/sjherp/app/memory/MemoryServiceTest.java`
- Create: `server/sjherp-app/src/test/java/com/sjherp/app/memory/MemoryIndexingServiceTest.java`
- Modify: `server/sjherp-app/src/main/java/com/sjherp/app/config/MemoryInfraConfig.java`

**Interfaces:**
- Consumes: Tasks 1–4 的领域端口、仓储与配置。
- Produces: Task 6 API 与 Task 7 调度/重建使用的唯一应用入口。

- [ ] **Step 1: 写 MemoryService 失败测试**

```java
@Test
void 创建先保存MySQL待索引再发布事件() {
    MemoryEntry created = service.create(command(), "user:1");
    assertThat(created.getIndexStatus()).isEqualTo(PENDING);
    verify(repository).save(created);
    verify(events).publishEvent(new MemoryIndexRequestedEvent(
            MemoryIndexOperation.UPSERT, created.getMemoryNo(), created.getId()));
}

@Test
void 更新创建新版本且旧版本被替代() {
    when(repository.findByMemoryNo("MEM-1")).thenReturn(Optional.of(version1));
    MemoryEntry version2 = service.replace("MEM-1", replacement(), "user:1");
    assertThat(version1.getStatus()).isEqualTo(SUPERSEDED);
    assertThat(version2.getVersion()).isEqualTo(2);
    assertThat(version2.getPreviousId()).isEqualTo(version1.getId());
}
```

- [ ] **Step 2: 运行确认 RED**

Run: `mvn -pl sjherp-app -am -Dtest=MemoryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，服务不存在。

- [ ] **Step 3: 实现 MemoryService**

公开接口固定为：

```java
@Transactional
@Audited(action = "memory.create", targetType = "memory")
public MemoryEntry create(MemoryEntryCommand command, String operator);

@Transactional
@Audited(action = "memory.replace", targetType = "memory")
public MemoryEntry replace(String memoryNo, MemoryEntryCommand command, String operator);

@Transactional
@Audited(action = "memory.expire", targetType = "memory")
public MemoryEntry expire(String memoryNo, String operator);

public MemoryEntry get(String memoryNo);
public PageResult<MemoryEntry> search(MemoryEntryQuery query);
```

编号复用 `DocumentNumberGenerator`，规则 `DocumentNumberRule.of("MEM")`。首版 `memoryKey=memoryNo`；替代版本共享旧 `memoryKey`。事务内只写 MySQL 和发布事件，不调用 HTTP。

事件类型固定为：

```java
public enum MemoryIndexOperation { UPSERT, DELETE }

public record MemoryIndexRequestedEvent(MemoryIndexOperation operation,
        String memoryNo, long memoryEntryId) {}
```

创建发布新记录 `UPSERT`；替代同时发布旧 id 的 `DELETE` 和新记录的 `UPSERT`；失效发布当前 id 的 `DELETE`。删除事件失败只保留脱敏 WARN，业务召回仍必须回查 MySQL `ACTIVE` 状态，因此旧 point 不会成为有效结果。

- [ ] **Step 4: 写索引编排失败测试**

```java
@Test
void 成功索引只写白名单payload并标记成功() {
    when(embedding.embed(entry.getContent(), DOCUMENT)).thenReturn(vector1024());
    assertThat(indexing.indexOne(entry.getMemoryNo(), "system:memory-indexer")).isTrue();
    verify(vectorIndex).upsert(argThat(point -> point.memoryEntryId() == entry.getId()
            && point.vector().size() == 1024));
    verify(state).markIndexed(entry.getMemoryNo(), COLLECTION, MODEL, 1024,
            "system:memory-indexer");
}

@Test
void qdrant失败不修改原文并记录下次重试() {
    doThrow(new QdrantVectorException("unavailable")).when(vectorIndex).upsert(any());
    assertThat(indexing.indexOne(entry.getMemoryNo(), "system:memory-indexer")).isFalse();
    verify(state).markFailed(eq(entry.getMemoryNo()), eq("Qdrant 暂不可用"),
            any(Instant.class), eq("system:memory-indexer"));
    assertThat(entry.getContent()).isEqualTo(ORIGINAL_CONTENT);
}
```

- [ ] **Step 5: 实现短事务状态服务和索引服务**

```java
public class MemoryIndexStateService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markIndexed(String memoryNo, String collection, String model,
            int dimension, String operator) {
        MemoryEntry entry = repository.findByMemoryNo(memoryNo)
                .orElseThrow(() -> new MemoryEntryNotFoundException(memoryNo));
        entry.markIndexed(collection, model, dimension, operator, Instant.now(clock));
        repository.save(entry);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String memoryNo, String error, Instant nextRetryAt,
            String operator) {
        MemoryEntry entry = repository.findByMemoryNo(memoryNo)
                .orElseThrow(() -> new MemoryEntryNotFoundException(memoryNo));
        entry.markIndexFailed(error, nextRetryAt, operator, Instant.now(clock));
        repository.save(entry);
    }
}
```

`MemoryIndexingService.indexOne` 不持有数据库事务：先读取快照，调用 Ollama，再调用 Qdrant，最后调用 `MemoryIndexStateService`。失败退避公式固定为 `min(30 * 2^retryCount, 3600)` 秒；使用 `Math.min(retryCount, 7)` 防止移位溢出。达到 `maxRetries` 后 `nextRetryAt=null`，只允许手工重试或重建。

公开接口固定为：

```java
public boolean indexOne(String memoryNo, String operator);
public void deletePoint(long memoryEntryId);

@Audited(action = "memory.retry_index", targetType = "memory_index")
public MemoryEntry retryIndex(String memoryNo, String operator);

@Audited(action = "memory.rebuild_index", targetType = "memory_index")
public RebuildResult rebuildIndex(String operator);

public record RebuildResult(int succeeded, int failed, long lastProcessedId) {}
```

`retryIndex` 先在短事务中调用 `markPending`，清零 `retryCount/nextRetryAt/lastIndexError`，再调用 `indexOne`。`deletePoint` 只删除 Qdrant 派生 point，不修改或删除 MySQL 行。

- [ ] **Step 6: 实现提交后监听**

```java
@Component
@ConditionalOnProperty(prefix = "sjherp.memory", name = "enabled", havingValue = "true")
public class MemoryIndexEventListener {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(MemoryIndexRequestedEvent event) {
        if (event.operation() == MemoryIndexOperation.UPSERT) {
            indexingService.indexOne(event.memoryNo(), "system:memory-indexer");
        } else {
            indexingService.deletePoint(event.memoryEntryId());
        }
    }
}
```

监听器捕获运行时异常并记录 WARN；异常不得反抛到已提交业务请求。失败状态由 `MemoryIndexingService` 落库。

- [ ] **Step 7: 运行服务测试确认 GREEN**

Run: `mvn -pl sjherp-app -am -Dtest=MemoryServiceTest,MemoryIndexingServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS；验证 HTTP 调用发生在真源事务提交后，失败不删除或覆盖原文。

- [ ] **Step 8: 提交 Task 5**

```bash
git add server/sjherp-app/src/main/java/com/sjherp/app/memory server/sjherp-app/src/main/java/com/sjherp/app/config/MemoryInfraConfig.java server/sjherp-app/src/test/java/com/sjherp/app/memory
git commit -m "feat: 编排大记忆最终一致性索引"
```

---

### Task 6: 管理 API、权限与审计

**Files:**
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryDtos.java`
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryController.java`
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryExceptionHandler.java`
- Create: `server/sjherp-app/src/test/java/com/sjherp/app/memory/MemoryControllerTest.java`
- Create: `server/sjherp-app/src/test/java/com/sjherp/app/memory/MemoryApiPermissionTest.java`
- Modify: `server/sjherp-domain/src/main/java/com/sjherp/domain/identity/Permission.java`
- Modify: `server/sjherp-domain/src/main/java/com/sjherp/domain/identity/RolePermissions.java`
- Modify: `server/sjherp-domain/src/test/java/com/sjherp/domain/identity/RolePermissionsTest.java`
- Modify: `server/sjherp-app/src/test/java/com/sjherp/app/audit/AuditWriteCoverageTest.java`
- Modify: `server/sjherp-app/src/main/java/com/sjherp/app/config/MemoryInfraConfig.java`

**Interfaces:**
- Consumes: Task 5 `MemoryService`/`MemoryIndexingService`。
- Produces: 受 `memory:manage` 保护的管理入口。

- [ ] **Step 1: 写权限失败测试**

```java
@Test
void 管理员和老板可创建记忆() throws Exception {
    for (Role role : List.of(Role.ADMIN, Role.BOSS)) {
        mockMvc.perform(post("/api/memories").with(asUser(role))
                .contentType(APPLICATION_JSON).content(CREATE_JSON))
                .andExpect(status().isCreated());
    }
}

@Test
void 会计和销售不可读取管理接口() throws Exception {
    for (Role role : List.of(Role.ACCOUNTANT, Role.SALES)) {
        mockMvc.perform(get("/api/memories").with(asUser(role)))
                .andExpect(status().isForbidden());
    }
}

@Test
void 未登录返回401() throws Exception {
    mockMvc.perform(get("/api/memories")).andExpect(status().isUnauthorized());
}
```

- [ ] **Step 2: 运行确认 RED**

Run: `mvn -pl sjherp-app -am -Dtest=MemoryApiPermissionTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，Controller 与权限点不存在。

- [ ] **Step 3: 增加权限点并同步角色**

```java
MEMORY_MANAGE("memory:manage", "大记忆管理")
```

`ADMIN` 通过 `EnumSet.allOf` 自动获得；`BOSS` 的显式集合加入 `MEMORY_MANAGE`；其他角色不加入。`RolePermissionsTest` 断言 BOSS/ADMIN true、ACCOUNTANT/SALES/WAREHOUSE false。

- [ ] **Step 4: 实现 DTO 和 Controller**

Controller 固定：

```java
@RestController
@RequestMapping("/api/memories")
@PreAuthorize("@perm.has('memory:manage')")
@ConditionalOnProperty(prefix = "sjherp.memory", name = "enabled", havingValue = "true")
public class MemoryController {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemoryResponse create(@Valid @RequestBody CreateMemoryRequest request) {
        return MemoryResponse.from(memoryService.create(request.toCommand(), CurrentUser.operator()));
    }

    @GetMapping("/{memoryNo}")
    public MemoryResponse get(@PathVariable String memoryNo) {
        return MemoryResponse.from(memoryService.get(memoryNo));
    }

    @GetMapping
    public PageResponse<MemoryResponse> search(
            @RequestParam(required = false) MemoryType type,
            @RequestParam(required = false) MemoryStatus status,
            @RequestParam(required = false) MemoryIndexStatus indexStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(memoryService.search(
                new MemoryEntryQuery(type, status, indexStatus, page, size)));
    }

    @PutMapping("/{memoryNo}")
    public MemoryResponse replace(@PathVariable String memoryNo,
            @Valid @RequestBody CreateMemoryRequest request) {
        return MemoryResponse.from(memoryService.replace(
                memoryNo, request.toCommand(), CurrentUser.operator()));
    }

    @PostMapping("/{memoryNo}/expire")
    public MemoryResponse expire(@PathVariable String memoryNo) {
        return MemoryResponse.from(memoryService.expire(memoryNo, CurrentUser.operator()));
    }

    @PostMapping("/{memoryNo}/retry-index")
    public MemoryResponse retry(@PathVariable String memoryNo) {
        indexingService.retryIndex(memoryNo, CurrentUser.operator());
        return MemoryResponse.from(memoryService.get(memoryNo));
    }

    @PostMapping("/rebuild-index")
    public RebuildResponse rebuild() {
        return RebuildResponse.from(indexingService.rebuildIndex(CurrentUser.operator()));
    }
}
```

请求 DTO 对 `title/content/type/sourceType/sourceRef` 使用 `@NotBlank/@NotNull/Size`；枚举由 Jackson 严格解析。响应包含内容、来源、版本、治理状态、索引状态、模型、维度和脱敏错误；不得返回向量。

- [ ] **Step 5: 实现异常映射**

`MemoryEntryNotFoundException -> 404`；参数/状态错误 -> 400/409；索引手工请求已接收但本地服务失败时返回当前 `FAILED` 状态，不把本地 URL、响应体或正文写入错误响应。

- [ ] **Step 6: 扩充审计覆盖**

在 `AuditWriteCoverageTest` 加 `MemoryService` 的 create/replace/expire 三条写路径，并断言：

```java
assertAudit("memory.create", "memory");
assertAudit("memory.replace", "memory");
assertAudit("memory.expire", "memory");
```

手工重试与重建在 `MemoryIndexingService` 上分别标注 `memory.retry_index`、`memory.rebuild_index`，并进入反射完整性守卫。

- [ ] **Step 7: 运行 API、权限、审计测试确认 GREEN**

Run: `mvn -pl sjherp-app -am -Dtest=MemoryControllerTest,MemoryApiPermissionTest,AuditWriteCoverageTest,RolePermissionsTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS；所有管理端点 ADMIN/BOSS 成功，其他角色 403，未登录 401。

- [ ] **Step 8: 提交 Task 6**

```bash
git add server/sjherp-domain/src/main/java/com/sjherp/domain/identity server/sjherp-domain/src/test/java/com/sjherp/domain/identity server/sjherp-app/src/main/java/com/sjherp/app/memory server/sjherp-app/src/test/java/com/sjherp/app/memory server/sjherp-app/src/test/java/com/sjherp/app/audit/AuditWriteCoverageTest.java server/sjherp-app/src/main/java/com/sjherp/app/config/MemoryInfraConfig.java
git commit -m "feat: 提供受控的大记忆管理入口"
```

---

### Task 7: 重试、全量重建与真服务集成验收

**Files:**
- Create: `server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryIndexRetryJob.java`
- Create: `server/sjherp-app/src/test/java/com/sjherp/app/memory/MemoryIndexRetryJobTest.java`
- Create: `server/sjherp-app/src/test/java/com/sjherp/app/memory/MemoryFoundationIntegrationTest.java`
- Modify: `server/sjherp-app/src/main/java/com/sjherp/app/memory/MemoryIndexingService.java`
- Modify: `server/sjherp-app/src/main/java/com/sjherp/app/config/MemoryInfraConfig.java`

**Interfaces:**
- Consumes: Tasks 2–6 全链路。
- Produces: 可恢复、可重建的 M6-T01 完整后端能力。

- [ ] **Step 1: 写重试任务失败测试**

```java
@Test
void 每批只处理到期且未超过上限的记录() {
    when(repository.findIndexCandidates(now, 50)).thenReturn(List.of(a, b));
    job.retryDueEntries();
    verify(indexing).indexOne(a.getMemoryNo(), "system:memory-indexer");
    verify(indexing).indexOne(b.getMemoryNo(), "system:memory-indexer");
    verifyNoMoreInteractions(indexing);
}
```

- [ ] **Step 2: 实现 MemoryIndexRetryJob**

```java
@Component
@ConditionalOnProperty(prefix = "sjherp.memory", name = "enabled", havingValue = "true")
public class MemoryIndexRetryJob {
    @Scheduled(fixedDelayString = "${sjherp.memory.indexing.retry-delay-seconds:30}000")
    public void retryDueEntries() {
        List<MemoryEntry> due = repository.findIndexCandidates(
                Instant.now(clock), properties.indexing().batchSize());
        for (MemoryEntry entry : due) {
            try {
                indexingService.indexOne(entry.getMemoryNo(), "system:memory-indexer");
            } catch (RuntimeException exception) {
                log.warn("记忆索引重试未完成: memoryNo={}, retryCount={}, errorType={}",
                        entry.getMemoryNo(), entry.getRetryCount(),
                        exception.getClass().getSimpleName());
            }
        }
    }
}
```

逐条捕获异常并继续下一条；单次最多 `batchSize`；日志只记录 `memoryNo`、重试次数和错误类别。

- [ ] **Step 3: 实现全量重建**

```java
@Audited(action = "memory.rebuild_index", targetType = "memory_index")
public RebuildResult rebuildIndex(String operator) {
    long afterId = 0;
    int succeeded = 0;
    int failed = 0;
    List<MemoryEntry> batch;
    do {
        batch = repository.findActiveAfterId(afterId, properties.indexing().batchSize());
        for (MemoryEntry entry : batch) {
            afterId = entry.getId();
            if (indexOne(entry.getMemoryNo(), operator)) succeeded++; else failed++;
        }
    } while (!batch.isEmpty());
    return new RebuildResult(succeeded, failed, afterId);
}
```

重建前再次调用 `ensureCollection`；单条失败不中止整批；不删除旧 collection；结果返回成功、失败和最后 id。

- [ ] **Step 4: 写 Testcontainers 真链路集成测试**

```java
@Tag("integration-db")
class MemoryFoundationIntegrationTest {
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.13.4")
            .withExposedPorts(6333);

    @Test
    void 真源写入索引失败恢复和全量重建闭环() {
        MemoryEntry entry = memoryService.create(command(), "user:1");
        indexingService.indexOne(entry.getMemoryNo(), "system:memory-indexer");
        assertThat(memoryService.get(entry.getMemoryNo()).getIndexStatus()).isEqualTo(INDEXED);

        qdrantTestSupport.recreateEmptyCollection();
        RebuildResult result = indexingService.rebuildIndex("user:1");
        assertThat(result.failed()).isZero();
        assertThat(qdrantTestSupport.pointIds()).contains(entry.getId());
        assertThat(qdrantTestSupport.payload(entry.getId()).toString())
                .doesNotContain(entry.getTitle(), entry.getContent());
    }
}
```

测试配置用确定性的 `EmbeddingClient` 生成固定 1024 维向量，不在 CI 拉取 Ollama 模型。另加：Qdrant 暂停/恢复后的 `FAILED -> INDEXED`；失效记录即使 point 暂存也被 MySQL 状态过滤；错误 collection 维度导致 context 启动失败。

- [ ] **Step 5: 运行 Task 7 单元测试**

Run: `mvn -pl sjherp-app -am -Dtest=MemoryIndexRetryJobTest,MemoryIndexingServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

- [ ] **Step 6: 运行真服务集成测试**

Run: `mvn -pl sjherp-app -am -Dgroups=integration-db -DexcludedGroups=none -Dtest=MemoryFoundationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: Docker 可用时 PASS；本机无 Docker 时记录环境阻塞，由 CI `backend-integration-db` job 执行同一测试。

- [ ] **Step 7: 提交 Task 7**

```bash
git add server/sjherp-app/src/main/java/com/sjherp/app/memory server/sjherp-app/src/test/java/com/sjherp/app/memory server/sjherp-app/src/main/java/com/sjherp/app/config/MemoryInfraConfig.java
git commit -m "feat: 支持大记忆索引重试与重建"
```

---

### Task 8: 环境、业务文档与最终质量闸门

**Files:**
- Create: `docs/业务-系统大记忆.md`
- Modify: `deploy/docker-compose.dev.yml`
- Modify: `docs/权限矩阵.md`
- Modify: `docs/产品路线图-0到1.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: Tasks 1–7 的最终行为和测试结果。
- Produces: 可复现环境、业务说明和 M6-T01 完成记录。

- [ ] **Step 1: 核对并记录 Ollama 开发环境**

在获得外部安装/下载许可后执行：

```powershell
ollama --version
ollama pull qwen3-embedding:0.6b
ollama list
```

Expected: 列表包含 `qwen3-embedding:0.6b`。若 `ollama` 未安装，先通过 Ollama 官方 Windows 安装包安装并重新打开终端；不得把模型文件提交仓库。

- [ ] **Step 2: 完成业务文档**

`docs/业务-系统大记忆.md` 必须明确：

- 记忆解决的业务问题和四类记忆。
- MySQL 真源、Qdrant 派生索引、本地 Ollama 的职责。
- 写入后 `PENDING/INDEXED/FAILED` 的用户含义。
- 更新产生新版本、失效不物删、来源与审计可追溯。
- 本批只有管理能力，聊天召回由 M6-T03 接入。

- [ ] **Step 3: 同步权限和路线图**

在 `docs/权限矩阵.md` 增加 `memory:manage`，仅 ADMIN/BOSS；在路线图 M6-T01 标记完成并记录模型、维度、最终一致性、可重建验收；在 `CLAUDE.md` 当前状态追加同一事实，避免文档口径漂移。

- [ ] **Step 4: 执行静态质量检查**

Run: `git diff --check`

Expected: 无空白错误。

Run: `rg -n "delete|remove" server/sjherp-domain/src/main/java/com/sjherp/domain/memory server/sjherp-infra/src/main/java/com/sjherp/infra/persistence/memory`

Expected: 业务真源聚合和仓储无物理删除方法；只允许 `VectorIndex.delete` 及其 Qdrant 实现出现派生索引删除。

- [ ] **Step 5: 执行分层回归**

Run: `mvn -pl sjherp-domain test`

Expected: PASS。

Run: `mvn -pl sjherp-infra -am test`

Expected: PASS。

Run: `mvn -pl sjherp-app -am test`

Expected: PASS。

- [ ] **Step 6: 执行全反应堆回归**

Run: `mvn test`

Expected: 所有默认测试 PASS，0 failures，`sjherp.memory.enabled=false` 时不连接 Ollama/Qdrant。

- [ ] **Step 7: 执行真实本地冒烟**

启动 Qdrant 与 Ollama 后，以 local profile 开启记忆：

```powershell
$env:SJHERP_MEMORY_ENABLED='true'
mvn -pl sjherp-app -am spring-boot:run -Dspring-boot.run.profiles=local
```

使用 Swagger 完成“创建记忆 → 查询 `INDEXED` → 逻辑失效 → Qdrant point 删除”的冒烟链路。不得把 token、密码或业务正文写入命令历史或日志截图。

- [ ] **Step 8: 提交文档与环境声明**

```bash
git add docs/业务-系统大记忆.md docs/权限矩阵.md docs/产品路线图-0到1.md CLAUDE.md deploy/docker-compose.dev.yml
git commit -m "docs: 完成大记忆基建交付说明"
```

- [ ] **Step 9: 进入分支完成流程**

调用 `superpowers:verification-before-completion` 复核最新测试证据，再调用 `superpowers:finishing-a-development-branch` 提供合并、PR 或保留分支选择。没有最新全量测试证据时不得宣称 M6-T01 完成。
