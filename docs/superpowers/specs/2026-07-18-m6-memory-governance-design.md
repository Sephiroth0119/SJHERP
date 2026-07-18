# M6-T04 系统大记忆治理设计

## 1. 目标与边界

M6-T04 在 M6-T01～T03 的 MySQL 真源、结构化写入和聊天召回基础上，补齐大记忆的人工治理闭环：识别重复与冲突候选，允许管理员查看、版本编辑、逻辑失效、冲突标记和冲突恢复，并提供轻量管理页面。

本阶段遵循“小步、可回退、不过度扩展”：

- 系统只识别候选，不自动合并、自动失效或自动裁决冲突。
- 仅 `ADMIN`、`BOSS` 可写入和治理，继续统一使用 `memory:manage`；普通已登录用户仍只通过 T03 聊天链路召回。
- MySQL `memory_entry` 仍是唯一真源，Qdrant 仍是可删除、可重建的派生索引。
- 所有状态变更经领域聚合和 `MemoryService`，事务化并写统一审计。
- 编辑继续创建新版本；删除在产品界面中实现为逻辑失效，禁止物理删除。
- 不新增语义去重、治理工单表、自动过期任务、聊天召回 API 或 Agent 治理工具。

## 2. 已有能力与缺口

T01 已提供：

- `MemoryStatus`：`ACTIVE`、`SUPERSEDED`、`EXPIRED`、`CONFLICT`。
- 原文 SHA-256 `contentHash`、类型、标题、来源、有效期、版本链和完整审计字段。
- 创建、单条查询、分页查询、版本替换、逻辑失效、索引重试和全量重建 API。
- 状态变化后发布派生索引 `UPSERT`/`DELETE` 事件。
- `memory:manage` 类级 API 权限，且仅授予 `ADMIN`、`BOSS`。

当前缺口是：

- `CONFLICT` 尚无领域状态流转和应用服务入口。
- 尚无可解释、确定性的重复/冲突候选查询。
- 尚无管理前端，现有 API 无可视化入口。
- 冲突记忆缺少安全恢复路径；现有失效只接受 `ACTIVE`。

## 3. 方案选择

采用“查询时确定性分组 + 人工治理”方案，不新增治理表或向量语义判定。

未采用的方案：

1. 持久化治理工单：审计维度更丰富，但需要新表、工单状态机和额外流程，超出本阶段范围。
2. Qdrant 语义去重：可发现标题不同的近义内容，但阈值不稳定、存在误报并引入外部服务依赖，不适合作为治理真相。

选择确定性分组的原因：规则可解释、结果可复现、无外部依赖、无数据库迁移，并能复用 T01 现有字段和接口。

## 4. 候选识别契约

候选只从当前租户的 `ACTIVE` MySQL 真源中产生，不读取 Qdrant。

### 4.1 重复候选

同一组必须同时满足：

- `memoryType` 相同；
- `contentHash` 相同；
- 组内至少两条 `ACTIVE` 记忆。

标题、来源或 `memoryKey` 不要求相同。相同正文在不同记忆类型下可能承担不同业务语义，因此不跨类型判重。

重复候选仅用于提示。系统不新增“重复”状态；管理员逐条核对来源后，自主决定保留项并对其余条目执行逻辑失效。

### 4.2 冲突候选

同一组必须同时满足：

- `memoryType` 相同；
- 标题相同；
- 至少存在两个不同的 `contentHash`；
- 组内至少两条 `ACTIVE` 记忆。

标题按领域创建后已去除首尾空白的存储值做完全相等比较；Jdbc 分组显式使用二进制字符串相等，避免数据库排序规则把不同标题宽松折叠。本阶段不做大小写归一、分词、同义词或向量近似判断。

### 4.3 查询上限

管理查询默认最多返回重复组和冲突组各 50 组，允许值为 1～100。上限按候选组控制，不截断已返回组内成员。查询按组内最新 `id` 倒序，便于优先处理近期问题。

候选查询是低频管理操作。T04 不增加迁移；现有 `(tenant_id, status, memory_type, id)` 治理索引先完成活动集过滤。若真实数据规模证明分组扫描成为瓶颈，再单独评估哈希/标题复合索引。

## 5. 领域状态流转

`MemoryEntry` 新增以下受控行为：

- `markConflict(operator, now)`：仅允许 `ACTIVE → CONFLICT`，更新操作人和更新时间，不改原文和版本。
- `activate(operator, now)`：仅允许 `CONFLICT → ACTIVE`；保留原有 `validTo`，且有效期已结束时拒绝恢复；将索引状态重置为 `PENDING` 并清空旧索引规格、重试和错误字段。
- `expire(operator, now)`：扩展为允许 `ACTIVE/CONFLICT → EXPIRED`；`SUPERSEDED/EXPIRED` 仍拒绝。

约束：

- `SUPERSEDED`、`EXPIRED` 是终态，不允许恢复。
- `CONFLICT` 不允许直接创建替代版本；管理员必须先恢复该条或将其失效，避免编辑动作隐式裁决冲突。
- `CONFLICT` 不可标记待索引、索引成功或索引失败。
- 任何非法流转均在修改字段前失败，聚合保持原状态。
- 冲突标记和恢复都不得延长或改写原有效期；已过有效期的冲突记忆只能逻辑失效。

## 6. 仓储与应用服务

### 6.1 仓储端口

`MemoryEntryRepository` 增加两个只读查询：

- `findDuplicateCandidates(tenantId, limit)`
- `findConflictCandidates(tenantId, limit)`

Jdbc 实现使用派生分组查询先选出有限候选组，再回表读取完整成员。所有条件显式包含租户和 `ACTIVE` 状态。

### 6.2 候选查询服务

新增只读 `MemoryGovernanceService.findCandidates(limit)`，将仓储结果按规则组成不可变候选组响应。该服务不修改状态、不访问 embedding 或 Qdrant。

### 6.3 整组冲突标记

`MemoryService.markConflict(memoryNos, operator)`：

1. 请求必须包含 2～50 个不重复记忆编号。
2. 在单一 MySQL 事务中重新读取所有条目。
3. 再次验证全部为 `ACTIVE`、同租户、同类型、同标题，并至少有两个不同 `contentHash`。
4. 任一条不存在、状态已变化或不再满足候选规则时，整体返回冲突错误，不做部分写入。
5. 全部调用聚合 `markConflict` 并保存。
6. 为每条记录发布 `DELETE` 索引事件；事件在事务提交后删除 Qdrant point。
7. 返回实现 `AuditTarget` 的批量治理结果；`targetCode` 使用首条记忆编号，摘要只包含动作、记忆编号和数量，不包含标题、正文或哈希。

方法标注 `@Audited(action = "memory.mark_conflict", targetType = "memory")`，一次明确的整组治理产生一条批量审计记录。

### 6.4 冲突恢复

`MemoryService.activate(memoryNo, operator)`：

1. 读取单条 MySQL 真源并调用聚合 `activate`。
2. 保存为 `ACTIVE/PENDING`。
3. 发布 `UPSERT` 事件；提交后按 T01 机制生成 embedding 并写入 Qdrant。
4. 标注 `@Audited(action = "memory.activate", targetType = "memory")`。

恢复只代表管理员明确允许该条重新参与召回，不自动处理同组其他冲突项。页面会提示先失效其他冲突项；后端仍允许逐条明确决策。

### 6.5 逻辑失效

继续复用 `MemoryService.expire` 和现有 API。领域扩展后，`ACTIVE` 与 `CONFLICT` 均可失效；两者都发布 `DELETE` 事件。不会删除 MySQL 原文、版本链或审计记录。

## 7. REST API

全部接口仍位于类级 `@PreAuthorize("@perm.has('memory:manage')")` 和 `sjherp.memory.enabled=true` 条件下。

### 7.1 查询治理候选

`GET /api/memories/governance/candidates?limit=50`

返回：

```json
{
  "duplicateGroups": [
    { "type": "BUSINESS_TERM", "entries": [/* MemoryResponse */] }
  ],
  "conflictGroups": [
    { "type": "BUSINESS_TERM", "title": "大客户口径", "entries": [/* MemoryResponse */] }
  ]
}
```

响应只对 `ADMIN/BOSS` 开放，因此可以复用包含原文和来源的 `MemoryResponse`，但不返回向量。

### 7.2 整组标记冲突

`POST /api/memories/governance/conflicts`

请求：

```json
{ "memoryNos": ["MEM-202607-0001", "MEM-202607-0002"] }
```

成功返回更新后的记忆列表。请求校验失败返回 `400`；条目状态已变化或不满足冲突组规则返回 `409`；不存在返回 `404`。

### 7.3 恢复冲突记忆

`POST /api/memories/{memoryNo}/activate`

成功返回恢复后的 `MemoryResponse`。非 `CONFLICT` 状态返回 `409`。

现有创建、分页、详情、替换、失效、索引重试和重建接口保持兼容。

## 8. 管理页面

前端不引入路由库、状态库、组件库或新测试框架。

### 8.1 入口权限

- `ModuleKey` 增加 `memory`。
- `Sidebar` 根据当前用户角色，仅对 `ADMIN`、`BOSS` 渲染“记忆治理”。
- `App` 同样校验角色后才渲染 `MemoryGovernancePage`，避免仅靠隐藏菜单。
- 后端 `memory:manage` 是最终权限边界；前端角色判断只改善体验。

### 8.2 页面结构

页面包含两个标签：

1. **记忆列表**：按类型、状态、索引状态分页筛选；显示编号、标题、类型、状态、版本、来源、更新时间和索引状态。
2. **治理候选**：分别显示重复组和冲突组；候选卡片展示完整来源、有效期、版本和正文，便于人工比较。

### 8.3 操作

- 查看：选中列表项显示详情。
- 编辑：仅 `ACTIVE` 可打开预填表单，调用现有 `PUT` 创建新版本。
- 失效：`ACTIVE/CONFLICT` 可执行；二次确认文案明确“不会物理删除，历史与审计继续保留”。
- 索引重试：仅 `FAILED` 的 `ACTIVE` 记忆显示。
- 重复组：不提供自动保留；管理员对确认多余的条目逐条失效。
- 冲突组：提供“整组标记冲突”，一次提交组内全部编号。
- 冲突条目：提供“恢复活动”与“失效”，由管理员明确处理。

每次成功写操作后重新加载当前列表和治理候选，防止页面继续显示陈旧状态。

### 8.4 关闭与异常状态

- 后端功能关闭或接口不可用时，页面显示“大记忆功能未启用或暂不可用”，不尝试绕过开关。
- `409` 显示“记忆状态已变化，请刷新后重试”，并提供刷新按钮。
- 网络错误复用现有 `ApiError` 中文提示。
- 加载、空数据和提交中状态都必须可见，提交期间禁用重复操作。

## 9. 索引与召回一致性

- 标记冲突或失效：MySQL 先提交治理状态，提交后删除 Qdrant point。
- 即使 Qdrant 删除失败，T03 的 MySQL 二次门禁只接受 `ACTIVE + INDEXED`，因此 `CONFLICT/EXPIRED` 不会被注入聊天。
- 恢复活动：MySQL 先保存为 `ACTIVE/PENDING`，索引成功后进入 `ACTIVE/INDEXED`；索引失败时沿用 T01 的失败重试状态。
- T04 不修改 T03 候选数量、提示格式、引用规则或 fail-open 行为。

## 10. 安全与审计

- 普通已登录用户仍可在聊天中只读召回，但无法访问任何 `/api/memories/**` 接口或页面入口。
- 原文、来源编号、URL、请求体、哈希和外部响应体不得写入日志或异常。
- 单条治理审计使用 `MemoryEntry.auditSummary()`；批量冲突审计只记录记忆编号和数量。
- 所有业务写均经 `MemoryService`；Controller、前端和仓储不得直接改状态。
- 不提供 `DELETE` SQL、物理删除仓储方法或 REST `DELETE` 接口。

## 11. 测试与验收

### 11.1 单元与 API 测试

- `MemoryEntryTest`：冲突标记、恢复、冲突失效、终态和非法流转不变性。
- 仓储测试：重复组、冲突组、不同类型、非活动状态、租户隔离、组数量上限。
- `MemoryServiceTest`：整组再校验、事务前全量验证、索引删除事件、恢复 UPSERT 事件和陈旧状态拒绝。
- `MemoryControllerTest`：候选响应、批量请求校验、恢复和错误映射。
- `MemoryApiPermissionTest`：`ADMIN/BOSS` 可访问；其他角色 `403`；未登录 `401`。
- `AuditWriteCoverageTest`：标记冲突、恢复和冲突失效均落审计，摘要无正文或哈希。
- T03 召回回归：`CONFLICT` 不可召回，恢复但未索引也不可召回。

### 11.2 前端验证

- `npm run build` 同时执行 TypeScript 检查和 Vite 生产构建。
- 人工检查 ADMIN/BOSS 入口、普通用户隐藏、列表筛选、详情、编辑、失效确认、候选分组和冲突恢复交互。
- 本阶段不为轻量页面引入 Vitest、Testing Library 或端到端框架。

### 11.3 真库验收

扩展 `MemoryFoundationIntegrationTest`，使用 MySQL 8.4 + Qdrant 真容器验证：

1. 两条同标题不同内容的活动记忆被识别为冲突候选。
2. 整组标记后 MySQL 全部为 `CONFLICT`，Qdrant points 被删除。
3. 恢复其中一条后先进入 `PENDING`，索引成功后为 `ACTIVE/INDEXED` 并重新写入 point。
4. 重复内容可被识别，人工失效后 MySQL 历史仍存在且 point 删除。
5. 治理期间 MySQL 始终是召回真源门禁。

本地 Docker 不可用时执行全部非容器测试与前端构建；真库用例由 GitHub CI `integration-db` 验收。

## 12. 文档与交付

实现完成后同步：

- `docs/业务-系统大记忆.md`：候选规则、人工治理、状态流转和页面行为。
- `docs/权限矩阵.md`：补充 T04 页面和治理端点仍由 `memory:manage` 保护。
- `docs/产品路线图-0到1.md`：记录 M6-T04 完成范围和验收结果。
- `CLAUDE.md`：更新当前能力与测试基线。

M6-T04 从 `codex/m6-t03-memory-recall` 派生独立分支并使用堆叠 PR；不合并或关闭现有 PR，不修改 T01～T03 已验收边界。
