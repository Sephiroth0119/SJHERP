# M6-T07 报告 Agent 化与主动会话推送设计

## 目标与边界

T07 只把 T05/T06 已落库的一致性运行报告接入现有 Agent 会话：用户可以问最近一次、指定 UTC 自然日（包括 `daysAgo=1` 的“昨天”）或指定 `CHK-` 运行编号，Agent 召回真实报告并解释；完成运行若含 `ERROR`，向启用中的 ADMIN/BOSS 活跃会话主动提醒。

本批不新增一致性规则、不复制业务规则到 prompt、不修改业务账、不新建会话、不建设报告管理前端、不改变报告管理 API 的 ADMIN/BOSS 权限，也不接入企微/钉钉网络通道。

## 召回契约

新增 `query_consistency_report` NORMAL 工具，无 `requiredPermission`，因此所有已登录用户可调用。参数三选一：

- `runNo`：精确召回一个报告；
- `date`：按 UTC `YYYY-MM-DD` 召回该自然日完成时间最近的一次报告；
- `daysAgo`：`0` 为今天、`1` 为昨天，最多回溯 365 天；
- 三者都不填时召回最近一次报告。

工具只读 `ConsistencyReportService`/`ConsistencyCheckRunRepository`，返回运行时间、来源、状态、严重度计数、有限明细和安全解释。金额/数量保留 `BigDecimal.toPlainString()` 字符串；最多返回 10 条明细，不能把“未找到”解释为“检查干净”，也不能声称系统已经自动修复。

## P0 主动会话推送

`ConsistencyCheckRunner` 在报告独立写事务成功返回后调用 `ConsistencyProactiveChannel`。实现 `ConsistencySessionPushChannel` 只处理 `COMPLETED + errorCount > 0`：筛选启用且角色含 ADMIN/BOSS 的用户，再筛选其 `ACTIVE` 会话，追加一条现有 AgentReply 协议的助手文本。消息只含稳定运行编号、ERROR/WARN 计数和召回提示，不复制差异正文。

消息正文带 `[CONSISTENCY_REPORT_PUSH:<runNo>]` 标记，重复调度或通道重试会跳过同一会话中的同一报告。通道异常只记录异常类型，不回滚已经保存的运行报告；WAITING_USER/CLOSED 会话不插入消息，避免打断 HITL 或已关闭会话。

## 权限、审计与持久化

报告召回是只读 Agent 工具，不放宽 `GET /api/consistency/reports/**` 的 ADMIN/BOSS 管理权限；P0 主动提醒沿用 T05 的 ADMIN/BOSS 接收边界。运行报告和站内通知继续复用 T05 的 append-only 存储与审计约定，不新增迁移，不提供删除入口。会话消息是现有会话持久化能力的追加，不写业务表，不绕过领域服务。

## 验收

- 工具 schema 合法、NORMAL、无权限点；按昨天/运行号/最近一次召回可解释报告，异常和明细数量受控。
- P0 只推送给启用 ADMIN/BOSS 的 ACTIVE 会话，同一运行同一会话幂等；WARN/clean 不推送。
- Java 21 聚焦测试、后端全量测试、前端构建通过；MySQL 8.4 + Qdrant 真库由 GitHub CI 门禁执行，本机 Docker 不可用。
