# M6-T08 缺口到 GitHub Issue 设计

## 范围与候选规则

仅 `gap_record.status=NEW` 的记录进入候选；已 triage、开发中、解决或驳回的记录不重复外发。候选按业务模块、严重度、标题/缺失能力/期望行为的 Unicode NFKC 规范化后小写、折叠空白并截断的确定性键聚类；不使用 LLM 或模糊相似度。每个候选保留最早记录为代表、最多 20 条场景样本和全部来源 gap 编号，内容只读、禁止物理删除。

## 审核、权限与配置

外部写入默认关闭（`sjherp.github.issue.enabled=false`），自动运行默认关闭（`sjherp.github.issue.auto-run=false`），人工审核默认开启（`sjherp.github.issue.manual-approval=true`）。人工审核开启时自动运行只聚类；关闭时仍须同时显式打开自动运行和外部写入才会自动审核/投递。仅 ADMIN/BOSS 可查看候选、审核、触发、重试和治理；普通登录用户、Agent 不具备 Issue 外发能力。Token、仓库和 API 地址只从配置/密钥注入，缺失时 fail-closed，绝不硬编码。

## 外部契约

标题为 `[SJHERP][模块][严重度] <代表标题>`；标签为 `sjherp-gap`、模块名和严重度。正文固定包含场景样本、期望行为、缺失能力、来源 gap 编号与候选幂等键，保证可追溯。客户端只接受结构化 `IssueResponse(number,url)`。

## 状态、幂等与失败

候选幂等键为完整规范化元组的 SHA-256，数据库唯一约束保证并发聚类不重复；来源通过 `gap_issue_source` append-only 关联表保存。投递采用 `PENDING/APPROVED/SENDING/SENT/FAILED`，数据库 CAS 抢占；外部成功后本地回写失败时，下一次先按 trace marker 查询并恢复，避免重复创建。失败保留错误类型/次数并允许 ADMIN/BOSS 重试；全程通过应用/领域服务写入并由 `@Audited` 记录，未提供删除接口。

本阶段不实现开发者 Agent（T09）或 Issue 关闭回写（T10），不执行真实 GitHub 验收；测试使用 fake 客户端。
