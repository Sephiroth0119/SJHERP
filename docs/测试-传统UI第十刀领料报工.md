# 传统 UI 第十刀：领料齐套与报工工作台

## 当前状态

从 `main`（第九刀生产工单）派生。在既有 `ProductionWorkbench` 上新增 **领料**、**退料**、**报工** 页签；齐套检查作为领料页只读面板。对接 main 已有 `/api/production/material-issues`、`/api/production/material-returns`、`GET /api/production/kitting-check`、`/api/production/reports`，**无 mock**。成本结转本刀不占位、不露出。不改 `server/`，不改预发 `web-dist` 静态包。

## 验收范围

- 生产模块页签：**BOM**、**工艺路线**、**需求计划**、**MRP**、**工单**、**领料**、**退料**、**报工**。只露本刀真实页，不放后续占位。
- 领料（权限 `production:material`）
  - 列表：`GET /api/production/material-issues?workOrderDocNo=&status=&page=&size=`（有值才传 workOrderDocNo/status）
  - 详情：`GET /api/production/material-issues/{docNo}`
  - 建单：`POST /api/production/material-issues`（201；工单号、领料仓、行：子件/应领≥0 字符串/实领>0 字符串/单位）
  - 流转：`POST /{docNo}/approve|post|cancel`（200）；草稿→审核→过账/作废
  - 工单须已开工（EXECUTING）；未开工后端拒绝，前端展示 `{error}` 原文并有可读提示
  - 过账扣库存（移动加权）；库存不足整批回滚，错误原文展示
- 齐套（同一权限，只读，不阻断领料）
  - `GET /api/production/kitting-check?workOrderDocNo=&warehouseId=`
  - 展示缺料清单（应领/可用/缺料）与 `kitted`；可用齐套结果填入领料行
- 退料（权限 `production:material`）
  - 列表：`GET /api/production/material-returns?materialIssueDocNo=&status=&page=&size=`
  - 详情：`GET /api/production/material-returns/{docNo}`
  - 建单：`POST /api/production/material-returns`（201；原领料单须已过账；行引用原领料明细；**不传单价**）
  - 流转：`POST /{docNo}/approve|post`（后端无 cancel）
  - 按原领料成本退回；超退前端拦截 + 后端 `{error}` 原文
- 报工（权限 `production:report`）
  - 列表：`GET /api/production/reports?workOrderDocNo=&status=&page=&size=`
  - 详情：`GET /api/production/reports/{docNo}`
  - 建单：`POST /api/production/reports`（201；一单合一：工时行 + 完工数量 + 报废数量可空不入库）
  - 流转：`POST /{docNo}/approve|post|cancel`
  - 过账完工入库并回写工单完工量；无已过账领料料费时后端拒绝，错误原文展示
- 数量/工时/金额一律字符串传输；列表/详情展示时去掉小数末尾 0（`5.000000` → `5`），不改提交真源。
- 商品走 `GET /api/catalog/products`，单位走 `GET /api/catalog/units`，仓库走 `GET /api/warehouse/warehouses`。开工工单选择在有 `production:wo` 时走 `GET /api/production/work-orders?status=EXECUTING`，无该权限则只填工单号、不发工单列表请求。
- 写动作同步 in-flight 守卫；列表/详情/单位/仓库/齐套均有 request gate。

## 权限

| 动作 | 权限 | 前端 |
|---|---|---|
| 进入生产模块 | 任一 `production:*` | 侧边栏既有规则 |
| 领料/退料/齐套 查写 | `production:material` | 无权限隐藏领料、退料页签；组件内另有无权限空态，不发起请求 |
| 报工查写 | `production:report` | 无权限隐藏报工页签；组件内空态且不发请求 |
| 从列表点选开工工单 | 另需 `production:wo` | 无工单权限时只手工填工单号 |

后端仍是最终授权边界。

## 手工验收

1. 有 `production:material`：能看领料/退料列表详情，能建单，能按状态审核/过账（领料草稿还可作废）；齐套可查缺料且不阻断领料。
2. 有 `production:report`：能看报工列表详情，能建单（工时+完工数量），能审核/过账入库/作废草稿。
3. 同时有 `production:wo`：领料/报工可从开工工单列表点选。只有 material/report、没有 wo → 页签仍在，工单号手填，不请求工单列表。
4. 无 `production:material` / `production:report`（仍能进生产模块）→ 对应页签不可见；即使进入组件也明确提示且不调接口。
5. 未开工工单领料/报工、超退、无料费报工过账：展示后端 `{error}` 原文。
6. 不改 `web-dist/`、`server/`。

## 未做

- 生产成本结转页签与入口（下一刀）。
- 领料/报工冲销（后端本批也不做 COMPLETED→REVERSED）。
- 退料作废（后端无 cancel 端点）。

## 结果

- `cd web && npm test`：226 项通过（含领料列表/创建窄 body/流转与齐套路径、退料不传单价与超退校验、报工工时数量字符串与流转、权限 fail-closed、in-flight 守卫、CJK 文案）。
- `cd web && npm run build`：TypeScript 检查与 Vite production build 通过。
- 未改 `web-dist/`、`server/`。
