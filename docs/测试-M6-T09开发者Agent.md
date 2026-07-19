# M6-T09 开发者 Agent v1 测试说明

状态：已完成（2026-07-19）。

## 验收与边界

- 仅接受 T08 `SENT + issueNumber` 候选；权限为 ADMIN/BOSS；任务状态、租约、CAS、失败恢复和审计均持久化。
- 通过 V36 持久化任务、分支/工作区、生成产物、定向测试、全量测试、CI 证据和失败摘要。
- 使用隔离工作区/路径策略与显式配置的 REST runner；生产默认 Disabled。
- fake runner 仅用于受控测试/demo，`ciGreen=false`，不冒充 CI。
- 不实现真实 shell 执行、真实 GitHub Issue 写入、自动 merge 或 deploy；外部动作必须显式配置并经过人工审核。
- 只有完整代码/测试/CI 证据通过且人工批准后，任务才可进入批准状态。

## 验证结果

- 指定 Java 21 `mvn verify`：Agent 70、Domain 851、Infra 61、App 1386，共 2368；0 failures、0 errors。
- 前端 `npm run build`：通过。
- 本机 Docker daemon 不可用，MySQL 8.4 Testcontainers 由 CI 执行。
- PR #14 HEAD `b141cd1`：后端、前端、MySQL 8.4 CI 全绿，`mergeStateStatus=CLEAN`；独立复审 Ready Yes。

## T10 交接边界

T10 使用 `POST /api/developer-agent/tasks/{id}/confirm-resolution`，仅 ADMIN/BOSS 可调用；请求必须包含 `reference` 与 `summary`。仅 APPROVED task 可确认，重复确认按 task 幂等。闭环在同一应用事务内推进来源 gap、写入 `closure_feedback` 真源、生成 GAP_CLOSURE 通知，并复用 T02 `MemoryWriteChannel` 写入 `GAP_SOLUTION`；不自动合并、部署或调用真实 GitHub。
