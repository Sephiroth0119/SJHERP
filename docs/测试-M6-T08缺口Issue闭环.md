# M6-T08 缺口→GitHub Issue 测试说明

状态：进行中。本文记录提交前已执行的本地验证；MySQL 8.4 Testcontainers 由 GitHub CI 作为最终真库门禁。

## 回归范围

- 领域：完整 Unicode NFKC 元组 SHA-256 聚类、超过 200 条的分页扫描、稳定最早代表、20 条去重场景样本、关闭开关、候选不存在、claim 后快照重载、trace 恢复、标签验收和 gateway 失败回写。
- 应用与 Web：ADMIN/BOSS 的 cluster/approve/deliver/reclaim 操作，SALES 403、匿名 401、GET 无写副作用；typed 404/409/503/502；scheduler 的人工/自动、重试上限和 enabled×auto-run Spring 装配矩阵。
- HTTP：GitHub create/search 的路径、查询、认证、User-Agent、API version、正文、labels 响应解析、非 2xx、缺字段与 `incomplete_results=true` fail-closed。
- MySQL 8.4：V35、来源双外键与 append-only、原子重复 upsert、APPROVED 场景合并（去重、最多 20 条）、并发聚类、lease token ABA、markSent 回写与租约清理、过期回收、候选/来源和 writer/finalizer 事务回滚。

## 本地执行结果（2026-07-19）

- Java 21 `mvn verify`：agent 70、domain 847、infra 60、app 1371，全部 0 failure / 0 error。
- 前端 `npm run build`：通过。
- 已尝试 `integration-db`。本机无可用 Docker，Testcontainers 在容器启动前报告 `Could not find a valid Docker environment`；相关 MySQL 8.4 用例已编译，等待 PR 的 MySQL CI 运行。

## 安全边界

默认 `enabled=false`、`auto-run=false`、`manual-approval=true`，测试仅使用本地 HTTP fake 或 fake client，绝不写入真实 GitHub 仓库。
