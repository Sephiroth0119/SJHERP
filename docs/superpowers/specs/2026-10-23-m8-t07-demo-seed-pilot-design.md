# M8-T07 演示种子试点

## 范围

默认种子企业现为真实试点客户「通润驱动」（pack id 仍为 sunshine）。本刀交付可重复的预发/本地试点装载能力：

1. 部署复用 M8-T01，不重造。
2. 经领域/应用服务装入 1 家种子企业（默认通润驱动；可选第二套备选演示包，单租户互斥）。
3. 种子含足以跑完一个会计期间的进销存+收付款数据；**不关账**（关账留给清单手测/预检）。
4. 用既有 `gap_record` / `record_process_gap` 记录试点问题。
5. 文档写清验收、回放、NO_WEB_DIST。

## 不做

- 改 `web-dist/`、领域状态机、多租户并行隔离。
- Flyway 灌业务种子（避免生产安装自动污染）。
- 自动审核过账绕开状态机（装载必须走 create→approve→post）。
- 空名称假数据。

## 装载入口

- Java `DemoSeedService`：唯一编排，只调已有 AppService / 档案领域服务 / `InventoryAdjustmentService.opening` / `GapRecordService`。
- REST `POST /api/pilot/demo-seed`，权限 `pilot:seed`（ADMIN/BOSS）。
- 可选启动：`SJHERP_DEMO_SEED=true`（默认 false）。
- 脚本 `deploy/seed-pilot.sh`：登录后调 REST；`--check` 供 CI dry-run。

## 种子包

| pack | 经营主体 | 切换 |
|---|---|---|
| `sunshine`（默认） | 通润驱动 | 空库直接装 |
| `qinghe` | 演示青禾食品（备选演示数据集） | 单租户互斥：已装另一包则拒绝，需空库或恢复后再装 |

档案名称禁止为空，不再要求「演示」前缀。账期取当前 UTC 月（与销售出库 COGS 凭证日一致）。数量：采购 100@12.50，销售 60@25.00，另给独立 SKU 做 OPENING。

## 验证

- 单测：默认通润驱动、空名拒绝、状态机顺序、幂等、不关账、互斥包。
- `integration-db`：真库装 sunshine → 关账预检可关且 0 ERROR、账期仍 OPEN。
- `seed-pilot.sh --check` 纳入部署资产 job。
