# 传统 UI 第九刀：生产工单

## 当前状态

从 `main`（第八刀需求计划与 MRP）派生。在既有 `ProductionWorkbench` 上新增 **工单** 页签，对接 main 已有 `/api/production/work-orders`，**无 mock**。领料、退料、齐套、报工、成本结转本刀不占位、不露出。不改 `server/`，不改预发 `web-dist` 静态包。

## 验收范围

- 生产模块页签：**BOM**、**工艺路线**、**需求计划**、**MRP**、**工单**。只露本刀真实页，不放后续占位。
- 工单
  - 列表：`GET /api/production/work-orders?productId=&status=&page=&size=`（有值才传 productId/status）
  - 详情：`GET /api/production/work-orders/{docNo}`
  - 手工建单：`POST /api/production/work-orders`（201；productId、plannedQty>0 字符串、unitId；可选 bomVersion/routingVersion/warehouseId/日期/备注）
  - 从 MRP 建议建单：`POST /api/production/work-orders/from-mrp`（201；mrpRunDocNo + productId；运行与 PRODUCTION 建议复用 `/api/production/mrp/runs`）
  - 流转：`POST /{docNo}/release|start|complete|cancel|reverse`（200）
  - 状态文案：DRAFT=计划、APPROVED=下达、EXECUTING=生产中、COMPLETED=完工、CANCELLED=作废、REVERSED=冲销
  - 按钮只按当前状态给出合法流转；非法流转以后端 409 `{error}` 原文展示
- 数量一律字符串传输；列表/详情展示时去掉小数末尾 0（`5.000000` → `5`），不改提交真源。
- 商品走 `GET /api/catalog/products`，单位走 `GET /api/catalog/units`，仓库走 `GET /api/warehouse/warehouses`。
- 写动作同步 in-flight 守卫；列表/详情/单位/仓库/MRP 建议均有 request gate。

## 权限

| 动作 | 权限 | 前端 |
|---|---|---|
| 进入生产模块 | 任一 `production:*` | 侧边栏既有规则 |
| 查工单列表/详情、建单、流转 | `production:wo`（后端类级覆盖全部端点含 GET） | 无权限隐藏工单页签；组件内另有无权限空态，不发起请求 |
| 从 MRP 建议建单的运行/建议选择 | 另需 `production:mrp` | 无 MRP 权限时隐藏「从 MRP 建单」 |

后端仍是最终授权边界。

## 手工验收

1. 有 `production:wo`：能看列表/详情，能手工建单，能按状态下达/开工/完工/作废/冲销。
2. 同时有 `production:mrp`：能选 MRP 运行 + PRODUCTION 建议建单。只有 wo、没有 mrp → 无「从 MRP 建单」，仍可手工建单。
3. 无 `production:wo`（仍能进生产模块）→ 工单页签不可见；即使进入组件也明确提示且不调接口。
4. 数量 `0`、未选商品/单位、结束日早于开始日：前端拦截；409 非法流转、403、校验失败展示后端 `{error}`。
5. 不改 `web-dist/`、`server/`。

## 未做

- 领料、退料、齐套、报工完工入库、生产成本结转页签与入口。
- 工单冲销的库存/凭证副作用（后端本批也不做）。

## 结果

- `cd web && npm test`：207 项通过（含工单列表/创建窄 body/from-mrp、流转路径、数量与日期校验、状态合法按钮、权限 fail-closed、in-flight 守卫、CJK 文案）。
- `cd web && npm run build`：TypeScript 检查与 Vite production build 通过。
- GitHub PR #57 三项 CI 全绿：前端 tsc+build 22s、后端 mvn verify 1m14s、MySQL 8.4 Testcontainers 6m2s。
- 未改 `web-dist/`、`server/`。
