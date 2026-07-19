# M6-T07 报告 Agent 化与主动会话推送测试

## 聚焦验收

Java 21 下执行：

```powershell
mvn -pl sjherp-app -am '-Dtest=QueryConsistencyReportToolTest,ConsistencySessionPushChannelTest,ConsistencyCheckRunnerTest,ConsistencyReportServiceTest,HighRiskToolPermissionTest,AuditWriteCoverageTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

覆盖 79 项：

- `query_consistency_report` 的 NORMAL/无权限点、合法 schema、`daysAgo=1`、运行号/日期召回、未找到/非法参数、解释、BigDecimal 字符串和最多 10 条明细；
- P0 仅投递启用 ADMIN/BOSS 的 ACTIVE 会话，WARN/clean 不投递，重复运行号推送幂等，推送不复制差异正文；
- 运行器只在报告持久化成功后触发主动通道，SQL/LLM fail-open、失败摘要脱敏、审计覆盖与既有工具注册回归。

## 真库与门禁

仓储按 UTC `completed_at` 半开区间查询指定自然日最近报告；MySQL 8.4 迁移/外键/DECIMAL 与跨模块全链路测试由 GitHub CI `integration-db` job 验收。本机 Docker 不可用，不宣称本地执行真库或 Qdrant 测试。
