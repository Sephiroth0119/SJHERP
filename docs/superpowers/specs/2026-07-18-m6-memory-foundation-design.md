# M6-T01 大记忆基建设计

## 1. 目标

为 SJHERP 建立可审计、可重建、默认不向外部服务发送业务文本的“大记忆”底座：

- MySQL 保存记忆原文和全部治理元数据，是唯一真源。
- Ollama 在本地生成文本向量。
- Qdrant 仅保存向量和最小过滤元数据，是可丢弃、可重建的派生索引。
- 为 M6-T02 记忆写入、M6-T03 对话召回和 M6-T10 闭环回写提供稳定接口。

本设计仅覆盖 M6-T01。自动摄入业务文档、聊天前置召回、冲突自动判定、前端治理界面和缺口创建 GitHub Issue 不在本批范围。

## 2. 已确认决策

1. Embedding 必须在本地运行，业务文本默认禁止外发。
2. 本地运行时采用 Ollama，首个模型采用 `qwen3-embedding:0.6b`。
3. 向量维度固定为 1024，Qdrant 距离算法采用 Cosine。
4. Java 侧定义独立抽象，首个实现调用 Ollama 原生 `/api/embed`，不引入 Java 内嵌 ONNX 或其他重量级推理框架。
5. MySQL 是记忆真源；Ollama 与 Qdrant 故障不得导致原文丢失。
6. Qdrant collection 不允许混写不同模型或不同维度的向量。模型切换必须创建新 collection 并全量重建。
7. 默认关闭大记忆功能；未安装 Ollama 时既有 ERP 能力仍可启动和运行。

## 3. 模块边界

### 3.1 `sjherp-domain`

新增 `com.sjherp.domain.memory`，职责仅限记忆真源及其业务不变式：

- `MemoryEntry`：记忆聚合根。
- `MemoryType`：`GAP_SOLUTION`、`BUSINESS_TERM`、`METRIC_DEFINITION`、`OPERATION_PREFERENCE`。
- `MemoryStatus`：`ACTIVE`、`SUPERSEDED`、`EXPIRED`、`CONFLICT`。
- `MemoryIndexStatus`：`PENDING`、`INDEXED`、`FAILED`。
- `MemoryEntryRepository`：保存、按编号和版本查询、分页查询待索引记录。
- `EmbeddingClient`：本地向量生成端口。
- `VectorIndex`：向量索引端口。

领域层不得依赖 Ollama、Qdrant、Spring 或 HTTP 客户端。

核心端口形态：

```java
public interface EmbeddingClient {
    EmbeddingVector embed(String text, EmbeddingPurpose purpose);
}

public record EmbeddingVector(String model, int dimension, List<Float> values) {}

public enum EmbeddingPurpose {
    DOCUMENT,
    QUERY
}

public interface VectorIndex {
    void ensureCollection(VectorCollectionSpec spec);
    void upsert(VectorPoint point);
    void delete(long memoryEntryId);
    List<VectorMatch> search(VectorQuery query);
}
```

`EmbeddingPurpose` 为 M6-T03 的查询指令与文档指令分离预留接口。本批只使用 `DOCUMENT`。

### 3.2 `sjherp-app`

新增 `com.sjherp.app.memory`：

- `MemoryService`：创建、版本替换、逻辑失效、查询记忆；所有写操作使用 `@Audited`。
- `MemoryIndexingService`：索引单条记忆、批量重试、全量重建。
- `MemoryIndexRetryJob`：按配置周期拉取到期的 `PENDING/FAILED` 记录。
- `MemoryController`：管理 API，统一要求 `memory:manage` 权限。
- `MemoryProperties`：绑定并校验 `sjherp.memory.*`。

应用层负责 MySQL 事务和最终一致性编排，不允许 Controller 或 Agent Tool 直接调用仓储、Ollama 或 Qdrant。

### 3.3 `sjherp-infra`

新增：

- `JdbcMemoryEntryRepository`：MySQL 仓储实现。
- `OllamaEmbeddingClient`：使用 JDK HTTP Client 调用 Ollama `/api/embed`。
- `QdrantVectorIndex`：使用 Qdrant HTTP API 管理 collection、point 和检索。

首版不引入 Ollama SDK、Qdrant gRPC SDK或 Spring AI，以控制依赖规模并保持自研抽象层。

## 4. 数据模型

在当前迁移序列之后新增迁移；存货分类迁移合入后预期为 `V32__memory_foundation.sql`。

`memory_entry` 字段：

| 字段 | 类型 | 规则 |
|---|---|---|
| `id` | BIGINT | 主键，也是 Qdrant point id |
| `tenant_id` | BIGINT | 当前统一写 0，保留租户边界 |
| `memory_no` | VARCHAR(32) | 业务编号，唯一 |
| `memory_key` | VARCHAR(128) | 同一逻辑记忆各版本共享 |
| `version` | INT | 从 1 递增；`tenant_id+memory_key+version` 唯一 |
| `previous_id` | BIGINT NULL | 指向被替代版本 |
| `memory_type` | VARCHAR(32) | 记忆类型枚举 |
| `title` | VARCHAR(200) | 必填 |
| `content` | LONGTEXT | 原文真源，必填 |
| `content_hash` | CHAR(64) | 规范化原文 SHA-256，用于幂等与后续去重 |
| `source_type` | VARCHAR(32) | `GAP_RECORD`、`USER_INPUT`、`BUSINESS_DOC`、`SYSTEM` 等 |
| `source_ref` | VARCHAR(128) | 来源编号或路径，可追溯 |
| `status` | VARCHAR(16) | 记忆治理状态 |
| `valid_from` | DATETIME(6) | 生效时间 |
| `valid_to` | DATETIME(6) NULL | 失效时间 |
| `index_status` | VARCHAR(16) | `PENDING/INDEXED/FAILED` |
| `indexed_collection` | VARCHAR(128) NULL | 最近成功索引的 collection |
| `embedding_model` | VARCHAR(128) NULL | 最近成功使用的模型 |
| `embedding_dimension` | INT NULL | 最近成功向量维度 |
| `retry_count` | INT | 默认 0 |
| `next_retry_at` | DATETIME(6) NULL | 下次允许重试时间 |
| `last_index_error` | VARCHAR(1000) NULL | 脱敏后的最近错误，不保存密钥或完整响应 |
| 审计字段 | VARCHAR/DATETIME | `created_by/created_at/updated_by/updated_at` |

必要索引：

- 唯一索引：`tenant_id, memory_no`。
- 唯一索引：`tenant_id, memory_key, version`。
- 重试索引：`tenant_id, index_status, next_retry_at, id`。
- 治理查询索引：`tenant_id, status, memory_type, id`。
- 来源索引：`tenant_id, source_type, source_ref`。

不建单独向量表。向量只存在 Qdrant，随时可以从 `content` 重算。

## 5. Qdrant collection

默认 collection 名为 `sjherp-memory-qwen3-0_6b-1024-v1`，配置必须显式包含模型代际和维度。

collection 规范：

- vector size：1024。
- distance：Cosine。
- point id：`memory_entry.id`。
- payload 仅允许：`memory_entry_id`、`tenant_id`、`memory_type`、`memory_status`、`source_type`。
- payload 禁止包含 `title`、`content`、业务单据正文、用户原话或密钥。

启动时：

1. `sjherp.memory.enabled=false`：不创建客户端、不连接 Ollama/Qdrant，既有应用照常启动。
2. 启用后，配置缺失或非法时启动失败并给出明确配置项。
3. collection 不存在时自动创建。
4. collection 已存在时校验维度和距离算法；任一不一致立即拒绝启动。

更换模型时创建新 collection，全量重建完成后再切换活动 collection；旧 collection 不自动删除，避免不可恢复的数据操作。

## 6. 写入与索引流程

MySQL、Ollama、Qdrant 不共享事务，采用“真源事务 + 可重试派生索引”：

1. `MemoryService` 在 MySQL 事务内创建 `MemoryEntry`，状态为 `ACTIVE/PENDING`。
2. 事务提交后发布本地事件，触发一次快速索引尝试。
3. `MemoryIndexingService` 调用 Ollama 生成向量。
4. 严格验证模型、维度、向量长度及所有数值均为有限数。
5. 幂等 upsert 到 Qdrant。
6. 成功后在独立 MySQL 事务中标记 `INDEXED` 并保存 collection、模型和维度。
7. 任一步失败则保留 MySQL 原文，标记 `FAILED`，记录脱敏错误和指数退避时间。
8. `MemoryIndexRetryJob` 定时重试到期记录；达到单轮重试上限后停止频繁请求，管理员可手工重试或执行重建。

API 创建成功只代表 MySQL 真源已保存。响应必须返回 `indexStatus`，不得在尚未索引时声称可以被召回。

## 7. 版本、失效与重建

### 7.1 更新

记忆内容不可原地覆盖。更新时：

1. 创建相同 `memory_key`、`version+1` 的新记录。
2. 新记录指向旧记录 `previous_id`。
3. 旧记录标记 `SUPERSEDED` 并填写 `valid_to`。
4. 新记录以 `PENDING` 进入索引流程。

### 7.2 删除

业务 API 不提供物理删除。失效操作将记录标记为 `EXPIRED`，随后删除对应 Qdrant point。即使 Qdrant 删除失败，M6-T03 召回也必须回查 MySQL，只返回 `ACTIVE` 版本。

### 7.3 重建

重建以 MySQL 为源分页扫描 `ACTIVE` 记录，逐批生成向量并幂等 upsert。流程必须：

- 可重复执行。
- 记录成功、失败和剩余数量。
- 单条失败不终止整批。
- 不修改 `content`、来源或业务状态。
- 不自动删除旧 collection。

## 8. 管理 API 与权限

新增权限 `memory:manage`，授予 `ADMIN/BOSS`。本批提供：

- `POST /api/memories`：人工创建一条记忆。
- `GET /api/memories/{memoryNo}`：查询单条及索引状态。
- `GET /api/memories`：按类型、状态、索引状态分页查询。
- `PUT /api/memories/{memoryNo}`：创建新版本并替代旧版本。
- `POST /api/memories/{memoryNo}/expire`：逻辑失效。
- `POST /api/memories/{memoryNo}/retry-index`：手工重试单条。
- `POST /api/memories/rebuild-index`：启动全量重建。

所有写操作必须经过 `MemoryService` 或 `MemoryIndexingService`，并写统一审计日志。M6-T02 的 Agent 写入工具也只能复用这些应用服务。

## 9. 配置

```yaml
sjherp:
  memory:
    enabled: false
    embedding:
      provider: ollama
      base-url: http://localhost:11434
      model: qwen3-embedding:0.6b
      dimension: 1024
      timeout-seconds: 60
    vector:
      provider: qdrant
      base-url: http://localhost:6333
      collection: sjherp-memory-qwen3-0_6b-1024-v1
      distance: COSINE
    indexing:
      retry-delay-seconds: 30
      batch-size: 50
      max-retries: 8
```

环境变量覆盖地址、模型和开关。配置不得包含业务规则或原文处理口径。

开发环境准备：

1. 安装 Ollama 并确保 `ollama` 可执行文件进入 `PATH`。
2. 执行 `ollama pull qwen3-embedding:0.6b`。
3. 保持 `deploy/docker-compose.dev.yml` 中 Qdrant v1.13.4。
4. 在 local profile 显式开启 `sjherp.memory.enabled=true`。

## 10. 错误处理

- Ollama/Qdrant 网络错误、超时或 5xx：索引失败并进入重试，不回滚 MySQL 真源。
- Ollama 4xx、模型不存在、响应不可解析：记录明确错误；超过重试上限后等待人工处理。
- 向量为空、含 NaN/Infinity、长度不是 1024：拒绝写入 Qdrant。
- Qdrant collection 规范不符：启动失败，禁止自动删除或重建现有 collection。
- 重复事件或重试：依赖固定 point id 和 upsert 保证幂等。
- Qdrant 返回旧 point：召回层回查 MySQL 状态，非 `ACTIVE` 一律丢弃。
- `last_index_error` 只保存脱敏摘要，不保存认证头、令牌或完整业务正文。

## 11. 测试与验收

### 11.1 领域单元测试

- 创建时必填字段、类型、来源和有效期校验。
- 新版本只能从当前活动版本生成，版本号单调递增。
- `SUPERSEDED/EXPIRED` 不可恢复为 `ACTIVE`。
- 索引状态转换和失败计数正确。
- 物理删除方法不存在。

### 11.2 客户端测试

- 使用本地假 HTTP 服务验证 Ollama 请求体、批量输入、超时、4xx/5xx、空响应和维度错误。
- 验证 Qdrant collection 创建、规范校验、upsert、delete、search 请求及异常映射。
- 测试 Qdrant payload 不含标题和原文。

### 11.3 真库集成测试

使用 Testcontainers MySQL 8.4 + Qdrant v1.13.4；EmbeddingClient 使用确定性测试实现，避免 CI 下载模型：

1. 写 MySQL `PENDING` → 索引 Qdrant → MySQL `INDEXED`。
2. 模拟 Qdrant 失败 → MySQL `FAILED` → 恢复后重试为 `INDEXED`。
3. 失效记忆即使 Qdrant 暂存旧点也不会被应用层返回。
4. 创建空 collection 后从 MySQL 全量重建，数量和 id 完全一致。
5. 不同维度或距离算法的既有 collection 导致启动校验失败。

### 11.4 回归与权限

- `sjherp.memory.enabled=false` 时执行全反应堆 `mvn test`，既有测试必须全绿。
- 管理 API 覆盖 ADMIN/BOSS 成功、其他角色 403、未登录 401。
- 审计覆盖测试确认创建、替代、失效、重试和重建均有审计事件。

## 12. 完成定义

M6-T01 只有在以下条件全部满足时完成：

1. MySQL 记忆真源、Ollama Embedding 抽象和 Qdrant 派生索引全部落地。
2. 任一外部本地服务故障均不丢失已提交的记忆原文。
3. Qdrant 可从 MySQL 完整重建，且不存在原文 payload。
4. 模型/维度混写被启动校验阻止。
5. 管理 API、权限、审计、单元测试和真库集成测试全部通过。
6. 默认关闭时不影响现有 ERP 与 Agent 功能。

