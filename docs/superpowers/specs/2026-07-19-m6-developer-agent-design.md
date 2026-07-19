# M6-T09 开发者 Agent v1 设计裁定

状态：已裁定，2026-07-19。范围是最小后端闭环，不是通用自治编程平台。

## 受控输入与状态

唯一输入是 T08 已成功发送的 `gap_issue_candidate`（`status=SENT` 且有 Issue 编号）。管理员启动时按候选唯一键幂等创建 `developer_agent_task`，并把全部来源缺口从 `TRIAGED` 推进到 `IN_DEVELOPMENT`；禁止自行抓取任意 GitHub Issue。任务状态为 `QUEUED → RUNNING → TESTING → AWAITING_REVIEW → APPROVED/FAILED/CANCELLED`，只有人工批准后才允许标记可合并；系统永不自动 merge、deploy 或解决缺口。

## 权限与高风险动作

启动、重试、取消、执行、查看敏感详情、人工批准仅 ADMIN/BOSS。普通登录用户没有开发者 Agent 入口。生成代码、执行命令、推送分支、创建 PR 均由显式策略控制；v1 默认 `disabled`，fake/local runner 是唯一默认可测试实现，真实外部副作用必须显式开启并逐项审计。

## 隔离与安全

每个任务使用独立分支与工作树；路径必须位于配置的仓库白名单。命令只允许配置 allowlist，带超时和输出截断；不继承凭证，不触碰主工作树，不执行物理删除、危险 git、merge、deploy。真实热部署按 ADR-001 的会话持久化+快速重启处理，不在 Agent 内实现。

## 持久化、租约与质量门禁

MySQL 是任务与状态真源，写入通过领域/应用服务，任务无物理删除。创建以候选唯一键幂等；执行通过 lease token CAS，过期可回收，重试次数受限，重启从状态恢复。代码和测试产物必须通过定向测试、全量测试和 CI；只有 CI 全绿且人工明确批准才进入 `APPROVED`，系统不得自行合并。T10 的原会话通知和大记忆回写不属于本阶段。

验收优先使用 fake runner + HTTP fake，真实 GitHub/命令默认关闭；真实闭环演示需由管理员显式开启并保留审计证据。
