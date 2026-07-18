# M6-T05 检查 Agent 框架设计

## 1. 目标与边界

M6-T05 在现有一致性校验能力外建立可调度、可注册、可追溯的运行框架：配置化定时执行，以 SQL 断言型规则为主、LLM 分析型规则为辅，将每次显式运行摘要落库，并在发现差异或运行失败时发送站内通知。

本阶段坚持小步演进：

- 保留现有 `ConsistencyCheckService`、`ConsistencyCheckDao` 和 17 条确定性规则的实现，不重写规则公式。
- 现有 17 条规则整体适配为首个注册规则，后续 T06 再按业务需要细分或新增规则。
- 建立 LLM 分析规则契约与 fail-open 语义，但默认不注册生产实现、不发起模型调用。
- 不实现 T06 核心规则扩充，不实现 T07 报表 Agent 化、聊天召回或主动会话推送。
- 不引入任意 SQL 配置执行器，不允许从数据库或前端提交可执行 SQL。
- 所有报告和通知只允许逻辑保留，不提供物理删除入口。

## 2. 已有能力与缺口

当前系统已有：

- `ConsistencyCheckService.check()` 调用 SQL 聚合 DAO，按 `BigDecimal` 口径完成 17 类确定性校验并返回内存 `ConsistencyReport`。
- `ConsistencyScheduledChecker` 支持 `sjherp.consistency.enabled` 和 cron 配置，默认每日 03:00、默认关闭。
- `GET /api/consistency/check` 供 `ADMIN/BOSS` 即时预览。
- Agent 工具 `run_consistency_check` 供已登录用户运行检查。
- 关账前置检查直接复用 `ConsistencyCheckService`。

当前缺口：

- 规则集合硬编码在单个服务中，没有稳定注册契约和执行顺序治理。
- 定时、REST 与 Agent 入口没有统一运行编排和来源标识。
- 报告只存在于内存，无法追溯历史运行、差异和失败。
- 定时任务只写日志，没有站内通知或可扩展通知通道。
- 尚无 LLM 分析规则接口和明确的故障隔离边界。

## 3. 方案选择

采用“外层适配器 + 统一运行器”方案。

### 3.1 推荐方案

将现有 `ConsistencyCheckService` 包装为一个 `CORE_SQL_ASSERTIONS` 注册规则。统一运行器负责选择规则、确定顺序、记录来源、持久化报告和分发通知。规则原有计算逻辑保持不变，关账仍直接使用纯校验服务。

优点是改动集中、回归面小，T05 可以先建立稳定框架，T06 再逐步增加或拆分规则。

### 3.2 未采用方案

1. 立即把 17 条校验拆成 17 个 Spring Bean：规则粒度更细，但会大面积重写已验证逻辑，与 T06 边界重叠。
2. 数据库配置任意 SQL：动态性高，但引入任意查询、权限、资源限制、结果映射和审计风险，不符合本阶段安全边界。

## 4. 分层与组件

### 4.1 规则契约

应用层新增：

- `ConsistencyRule`：提供稳定 `code`、`order`、`kind` 和 `evaluate(context)`。
- `ConsistencyRuleKind`：`SQL_ASSERTION`、`LLM_ANALYSIS`。
- `ConsistencyRuleRegistry`：收集规则，启动或首次使用时校验规则编码唯一，并按 `order + code` 稳定排序。
- `ConsistencyRuleResult`：包含该规则产生的差异和可选分析结果，不允许携带原始 SQL、提示词或外部响应体。
- `ConsistencyRuleContext`：包含租户、运行编号和触发来源等最小上下文，不把登录令牌传给规则。

`CoreSqlAssertionRule` 调用现有 `ConsistencyCheckService.check()`，将其报告原样映射为规则结果。现有 17 类 `ConsistencyCheckType`、严重级别和 `BigDecimal` 计算保持不变。

### 4.2 LLM 分析边界

`LLM_ANALYSIS` 与 SQL 断言规则使用同一注册表，但只能在全部确定性规则结束后执行。其输入是经过结构化、最小化的确定性事实，不是数据库连接或任意 SQL 能力。

T05 默认没有生产 LLM 规则，因此正常运行的分析状态为 `SKIPPED`。未来规则失败时：

- 不丢弃或降级确定性差异。
- 报告仍为已完成，分析状态记为 `FAILED`。
- 仅保存安全错误类型，不保存提示词、业务正文、模型响应或堆栈。
- 不因 LLM 失败把干净的确定性检查伪造成业务异常。

### 4.3 统一运行器

新增 `ConsistencyCheckRunner`，支持来源：

- `SCHEDULED`：配置化定时任务。
- `MANUAL_API`：管理端显式运行。
- `AGENT`：现有 Agent 工具运行。

执行顺序：

1. 生成不可变运行编号并记录开始时间。
2. 从注册表读取 SQL 断言规则并依序执行。
3. 汇总确定性差异及严重级别计数。
4. 执行已启用的 LLM 分析规则；T05 默认跳过。
5. 在独立写事务中保存运行摘要和差异明细。
6. 对非干净或失败运行创建站内通知。
7. 报告提交后调用扩展通知通道；通道异常不回滚报告。

运行器本身不把所有查询包在长写事务中。规则读取结束后，由独立持久化服务一次性写入报告、明细和站内通知。

若确定性规则抛出异常，运行器尽力在新事务中保存 `FAILED` 摘要和安全错误类型并创建通知，然后向同步调用方返回失败；数据库本身不可用时只能安全记录错误类别，不能伪造已落库结果。

### 4.4 关账隔离

`PeriodCloseService` 继续直接调用 `ConsistencyCheckService.check()`。关账前置检查属于业务门禁，不自动创建运营报告或通知，避免一次关账动作产生两套审计和重复提醒。

## 5. 数据模型

新增 V33 迁移，所有表预留 `tenant_id`，当前固定租户为 0。

### 5.1 `consistency_check_run`

每次 `SCHEDULED`、`MANUAL_API`、`AGENT` 显式运行均保存一行：

- `id`、`tenant_id`、唯一 `run_no`
- `trigger_type`、`requested_by`
- `started_at`、`completed_at`
- `status`：`COMPLETED`、`FAILED`
- `clean`
- `total_count`、`error_count`、`warn_count`、`info_count`
- `analysis_status`：`SKIPPED`、`SUCCEEDED`、`FAILED`
- 可选 `analysis_summary`
- 可选安全 `failure_type`
- `created_at`

`requested_by` 只保存系统标识或内部操作人标识，不保存令牌。失败信息只保存受控类型，不落原始异常消息。

### 5.2 `consistency_check_break`

仅当存在差异时写入：

- `id`、`tenant_id`、`run_id`、`sequence_no`
- `rule_code`、`check_type`、`object_key`
- `expected_value DECIMAL(24,6)`、`actual_value DECIMAL(24,6)`，可空
- `severity`、受控 `message`
- `created_at`

当前确定性规则的数字字符串在应用层按 `BigDecimal` 精确转换后写入 DECIMAL。未来非数值分析规则不得把描述文本塞入金额或数量字段。

### 5.3 `system_notification`

独立通知中心按接收人保存：

- `id`、`tenant_id`、`recipient_user_id`
- `category`、`severity`
- `title`、安全摘要 `content`
- `source_type`、`source_ref`
- `read_at`、`created_at`

`recipient_user_id` 关联 `sys_user`。`(tenant_id, recipient_user_id, source_type, source_ref)` 唯一，确保同一报告对同一用户幂等。T05 不提供删除接口；已读只写 `read_at`。

## 6. 通知策略

站内通知为必选通道：

- 只向状态为启用且角色包含 `ADMIN` 或 `BOSS` 的用户创建通知。
- 干净且成功的运行只落报告，不通知。
- 发现任意差异或确定性运行失败时通知。
- 通知只包含运行编号、来源、严重级别计数和查看提示，不复制全部差异或敏感业务正文。

定义 `NotificationChannel` 扩展契约。T05 仅实现 `InAppNotificationChannel`；企业微信、钉钉等不实现配置、鉴权或网络调用。未来外部通道必须在报告提交后执行并 fail-open。

## 7. REST 与 Agent 接口

### 7.1 一致性检查

- 保留 `GET /api/consistency/check`：`ADMIN/BOSS` 的即时、不落库预览，兼容现有调用和 GET 无副作用语义。
- 新增 `POST /api/consistency/runs`：`ADMIN/BOSS` 触发并持久化一次 `MANUAL_API` 运行。
- 新增 `GET /api/consistency/reports`：`ADMIN/BOSS` 分页查询运行摘要。
- 新增 `GET /api/consistency/reports/{runNo}`：`ADMIN/BOSS` 查询摘要和差异明细。

列表不返回分析原始输入、SQL 或异常消息。不存在返回 404，非法分页返回 400。

### 7.2 Agent 工具

现有 `run_consistency_check` 改为调用统一运行器，来源为 `AGENT`，操作人使用经过规范化的当前用户内部标识。保持现有登录用户可调用边界，不额外放宽管理报告查询权限。

工具输出继续限制差异条数并使用结构化摘要；完整历史报告仅 `ADMIN/BOSS` 可经管理 API 查看。异常运行会通知管理员和老板，不注入聊天记忆，也不主动创建会话消息。

### 7.3 通知中心

- `GET /api/notifications`：已登录用户分页查看自己的通知。
- `GET /api/notifications/unread-count`：查询自己的未读数。
- `POST /api/notifications/{id}/read`：仅将自己的通知标为已读；重复调用幂等。

后端必须按当前用户 ID 强制过滤，不接受客户端传入接收人。

## 8. 前端范围

前端只增加轻量通知入口，不建设新的治理页面：

- 顶部栏显示通知铃铛和未读数。
- 点击后加载当前用户最近通知，支持单条标记已读。
- 无通知、加载中、加载失败和未读状态清晰可见。
- 所有已登录用户可见入口；T05 的一致性通知实际只投递给 `ADMIN/BOSS`。
- 不引入新的状态库、组件库或测试框架。

报告运行和历史查询先通过后端 API 交付；T05 不新增报告管理前端，以控制改动范围。

## 9. 审计、安全与幂等

- `POST /api/consistency/runs` 和通知已读均经应用服务，不允许 Controller 或 Jdbc 适配器直接拼装业务状态。
- 报告本身记录来源、操作人和时间，是检查运行的可追溯凭证；管理端显式运行同时接入统一审计切面。
- Agent 来源必须从受信 ToolContext 获取，客户端不能伪造 `requested_by`。
- 日志、异常和通知不记录 SQL、请求体、访问令牌、提示词、模型响应或完整差异正文。
- 运行编号唯一；通知来源唯一键保证重试不重复投递。
- 无任何 REST `DELETE`、仓储删除方法或物理删除 SQL。

## 10. 测试与验收

### 10.1 单元与应用测试

- 注册表：稳定排序、重复编码拒绝、规则类型分组。
- 运行器：SQL 规则先执行、每次显式运行摘要落库、有差异才落明细。
- LLM：无规则时 `SKIPPED`；模拟分析失败时确定性结果保留且状态为 `FAILED`。
- 通知：干净运行不通知；差异/失败只通知启用的 `ADMIN/BOSS`；重复分发幂等。
- 失败处理：确定性异常保存安全失败摘要，不泄露原始消息。
- 关账回归：仍走纯校验服务，不产生框架报告。
- Controller：运行与报告仅 `ADMIN/BOSS`；通知只能读取和更新本人。
- Agent 工具：来源、操作人、结果上限和异常映射正确。
- 定时任务：继续读取配置化 cron，并改为调用统一运行器。

### 10.2 持久化与真库测试

使用 MySQL 8.4 验证：

1. V33 表结构、唯一键、外键和 DECIMAL 精度正确。
2. 干净运行保存摘要且无明细、无通知。
3. 差异运行原子保存摘要、明细和每位合格接收人的通知。
4. 已读幂等和接收人隔离。
5. 报告分页、详情和租户条件正确。
6. 不影响现有一致性规则、关账和此前 M6 记忆真库用例。

本地 Docker 不可用时完成全部非容器测试和前端生产构建；真库验收由 GitHub CI `integration-db` 完成。

## 11. 文档与交付

实现完成后同步：

- `docs/业务-数据一致性校验.md`：运行来源、落库、规则注册和通知语义。
- `docs/权限矩阵.md`：检查运行/报告权限和个人通知权限。
- `docs/产品路线图-0到1.md`：标记 M6-T05 完成范围和验证结果。
- `CLAUDE.md`：当前能力、迁移版本和测试基线。

M6-T05 从 `codex/m6-t04-memory-governance` 派生堆叠分支与 PR，不合并或关闭既有 PR，不修改 T01～T04 已验收边界。
