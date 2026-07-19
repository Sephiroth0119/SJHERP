# M6-T09 开发者 Agent v1 设计裁定

状态：已裁定，2026-07-19。范围是最小后端闭环，不是通用自治编程平台。生产默认 fail-closed。

## 受控输入与状态

唯一输入是 T08 已成功发送的 `gap_issue_candidate`（`status=SENT` 且有 Issue 编号）。管理员启动时按候选唯一键幂等创建 `developer_agent_task`，并把全部来源缺口从 `TRIAGED` 推进到 `IN_DEVELOPMENT`；禁止自行抓取任意 GitHub Issue。任务状态为 `QUEUED → RUNNING → TESTING → AWAITING_REVIEW → APPROVED/FAILED/CANCELLED`，只有人工批准后才允许标记可合并；系统永不自动 merge、deploy 或解决缺口。

## 权限与高风险动作

启动、重试、取消、执行、查看敏感详情、人工批准仅 ADMIN/BOSS。普通登录用户没有开发者 Agent 入口。生成代码、执行命令、推送分支、创建 PR 均由显式策略控制；v1 默认 `disabled`，fake/local runner 是唯一默认可测试实现，真实外部副作用必须显式开启并逐项审计。

## 隔离与安全

每个任务使用独立分支与工作树；`WorkspacePolicy` 校验规范化路径、realpath（目标不存在时校验父目录）位于仓库白名单、任务目录独立且分支名符合约束。`CommandPolicy` 只接受精确 argv allowlist，不拼接 shell，强制超时/输出截断并清空凭证环境；拒绝 `..` 逃逸、危险 git、merge、deploy 和删除。本批没有真实 local executor，CommandPolicy 是未来执行端口的约束，生产没有任何命令路径；真实 runner 禁用时 API 返回 503，不得 claim 或推进状态。真实热部署按 ADR-001 的会话持久化+快速重启处理，不在 Agent 内实现。

## 持久化、租约与质量门禁

MySQL 是任务与状态真源，写入通过领域/应用服务，任务无物理删除。创建以候选唯一键幂等；claim 和 finalize/fail 是短事务 CAS，长时间 runner 在事务外执行，过期可回收，ABA token 必须拒绝。持久化结构化代码/测试产物清单、分支/工作树、定向测试、全量测试、CI 状态及证据。只有这些证据全部满足且人工明确批准才进入 `APPROVED`，系统不得自行合并。T10 的原会话通知和大记忆回写不属于本阶段。

验收优先使用 fake runner + HTTP fake，runner 必须接收已锁定的 T08 候选快照（标题、场景、期望、缺失能力、来源），不得自行抓取 Issue。fake demo 只产生结构化示例产物和测试证据，CI 证据仍为 false，不能进入审核或冒充真实闭环。真实 GitHub/命令默认关闭；真实闭环演示需由管理员显式开启并保留审计证据。
