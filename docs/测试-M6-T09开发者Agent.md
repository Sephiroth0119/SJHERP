# M6-T09 开发者 Agent v1 测试说明

状态：实现完成，等待堆叠 PR 的 MySQL 8.4 CI 与独立终审。

## 已覆盖

- 受控输入：仅 T08 `SENT + issueNumber` 候选可创建任务；候选唯一键幂等，来源缺口按领域服务进入 `IN_DEVELOPMENT`。
- 状态与租约：`QUEUED → RUNNING → TESTING → AWAITING_REVIEW → APPROVED`；claim/finalize/fail 短事务 CAS；RUNNING/TESTING 过期回收；旧 token ABA 拒绝；失败类型/摘要持久化。
- 质量门禁：结构化产物、定向测试、全量测试、CI 证据分字段保存；缺任一项停在 FAILED；人工批准由领域模型和 SQL 双重校验。
- 安全：生产 runner 默认 Disabled，禁用在 claim 前返回 503；fake 仅显式 demo，且 `ciGreen=false`，不能进入审核。runner 只接收已锁定的 T08 候选快照，不抓任意 Issue。
- 隔离策略：分支命名、仓库白名单、规范化路径与 realpath/父目录校验；命令精确 allowlist、危险命令拒绝、超时约束、输出截断契约。当前没有真实 local executor，生产无命令执行路径。

## 验证结果

- Java 21 `mvn verify`：Agent 70、Domain 全量、Infra 全量、App 1377，0 failure / 0 error。
- 前端 `npm run build`：通过。
- MySQL 8.4 Testcontainers：本机 Docker 不可用，等待 GitHub CI 真库门禁。
- 测试不写真实 GitHub，不执行真实 shell、merge 或 deploy。
