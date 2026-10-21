# 传统 UI 第八刀：需求计划与 MRP

## 当前状态

从 `main`（第七刀生产工作台 BOM + 工艺路线）派生。在既有 `ProductionWorkbench` 上新增 **需求计划**、**MRP** 两页签，对接 main 已有 `/api/production/demand-plans` 与 `/api/production/mrp/runs`，**无 mock**。后续工单、领料、报工、成本结转本刀不占位、不露出。不改 `server/`，不改预发 `web-dist` 静态包。

## 验收范围

- 生产模块页签：**BOM**、**工艺路线**、**需求计划**、**MRP**。只露本刀真实页，不放后续占位。
- 需求计划
  - 列表：`GET /api/production/demand-plans?status=&page=&size=`（有值才传 status）
  - 详情：`GET /api/production/demand-plans/{docNo}`
  - 新建：`POST /api/production/demand-plans`（201；默认启用）
  - 整体更新行：`PUT /api/production/demand-plans/{docNo}`（planDate / remark / lines 整体替换）
  - 无 DELETE、无启停接口（后端本批未提供 enable/disable）
  - 行字段：productId、quantity>0、unitId、dueDate 可空
- MRP
  - 历史：`GET /api/production/mrp/runs?page=&size=`（仅头信息）
  - 详情：`GET /api/production/mrp/runs/{docNo}`（含 suggestions）
  - 触发：`POST /api/production/mrp/runs`（201；warehouseId>0、includeForecast、includeSalesOrder、remark?）
  - 建议行按 `PRODUCTION` / `PURCHASE` 分流展示；数量字符串展示
- 商品选择走真实 `GET /api/catalog/products`；单位走 `GET /api/catalog/units`；仓库走现有 `GET /api/warehouse/warehouses`（与库存盘点/调拨页一致）。
- 数量一律字符串（toPlainString 口径）；写动作同步 in-flight 守卫；列表/详情/单位/仓库均有 request gate。

## 权限

| 动作 | 权限 | 前端 |
|---|---|---|
| 进入生产模块 | 任一 `production:*` | 侧边栏既有规则 |
| 查需求计划 | 登录即可 | 需求计划页签始终可见 |
| 新建/整体改行 | `production:plan` | 无权限隐藏写按钮 |
| 查 MRP 历史/详情、触发运行 | `production:mrp` | 无权限隐藏 MRP 页签；组件内另有无权限空态，不发起请求，避免 403 刷屏 |

后端仍是最终授权边界。

## 手工验收

1. 有 `production:plan`：可建/改需求计划。无该权限的已登录用户（仍能进生产模块）→ 列表可查、无「新建需求计划 / 编辑行」。
2. 有 `production:mrp`：可选仓触发运行、看历史与生产/采购建议行。无该权限 → MRP 页签不可用；即使进入组件也明确提示无权限且不调接口。
3. 数量 `0`、空行、商品+单位重复、未选仓、预测与销售订单都未勾：前端拦截；后端 `{error}` 展示在页内 alert。
4. 不改 `web-dist/`、`server/`。

## 未做

- 工单、领料、退料、齐套、报工完工入库、生产成本结转页签与入口。
- 需求计划启停（后端无对应 REST）。
- 建议转工单 / 转采购单。

## 结果

- `cd web && npm test`：199 项通过（含需求计划列表/创建窄 body/更新空备注与交期、数量 0 与空行拦截、商品+单位去重、MRP 历史/详情数量字符串、触发窄 body、未选仓与双来源关闭拦截、权限 fail-closed、in-flight 守卫、仓库选择器与生产/采购分流、CJK 文案）。
- `cd web && npm run build`：TypeScript 检查与 Vite production build 通过。
- GitHub PR #56 三项 CI 全绿：前端 tsc+build 26s、后端 mvn verify 1m12s、MySQL 8.4 Testcontainers 7m9s。
- 未改 `web-dist/`、`server/`。
