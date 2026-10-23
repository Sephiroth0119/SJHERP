# M8-T07 演示种子试点

## 范围

产品方先用「演示数据干跑」，暂不接真实企业。本刀交付可重复的预发/本地演示试点能力：

1. 部署复用 M8-T01，不重造。
2. 经领域/应用服务装入 1 家虚构演示企业（可选第二套种子包，单租户互斥）。
3. 种子含足以跑完一个会计期间的进销存+收付款数据；**不关账**（关账留给清单手测/预检）。
4. 用既有 `gap_record` / `record_process_gap` 记录试点问题。
5. 文档写清验收、回放、NO_WEB_DIST。

## 不做

- 改 `web-dist/`、领域状态机、真实客户名、多租户并行隔离。
- Flyway 灌业务种子（避免生产安装自动污染）。
- 自动审核过账绕开状态机（装载必须走 create→approve→post）。
- 空名称假数据。

## 装载入口

- Java `DemoSeedService`：唯一编排，只调已有 AppService / 档案领域服务 / `InventoryAdjustmentService.opening` / `GapRecordService`。
- REST `POST /api/pilot/demo-seed`，权限 `pilot:seed`（ADMIN/BOSS）。
- 可选启动：`SJHERP_DEMO_SEED=true`（默认 false）。
- 脚本 `deploy/seed-pilot.sh`：登录后调 REST；`--check` 供 CI dry-run。

## 种子包

| pack | 经营主体（虚构） | 切换 |
|---|---|---|
| `sunshine`（默认） | 演示阳光文具 | 空库直接装 |
| `qinghe` | 演示青禾食品 | 单租户互斥：已装另一包则拒绝，需空库或恢复后再装 |

档案与单据名称一律 `演示*` / `Demo*`。账期取当前 UTC 月（与销售出库 COGS 凭证日一致）。数量：采购 100@12.50，销售 60@25.00，另给独立 SKU 做 OPENING。

## 验证

- 单测：前缀守门、状态机顺序、幂等、不关账、互斥包。
- `integration-db`：真库装 sunshine → 关账预检可关且 0 ERROR、账期仍 OPEN。
- `seed-pilot.sh --check` 纳入部署资产 job。
