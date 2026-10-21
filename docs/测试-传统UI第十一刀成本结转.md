# 传统 UI 第十一刀：成本结转工作台

## 当前状态

从 `main`（第十刀领料齐套与报工）派生。在既有 `ProductionWorkbench` 上新增 **成本结转** 页签，对接 main 已有 `/api/production/cost-settlements`，**无 mock**。不改 `server/`，不改预发 `web-dist` 静态包。

## 验收范围

- 生产模块页签：**BOM**、**工艺路线**、**需求计划**、**MRP**、**工单**、**领料**、**退料**、**报工**、**成本结转**。
- 成本结转（权限 `production:cost`，含查询；ADMIN / BOSS / ACCOUNTANT）
  - 列表：`GET /api/production/cost-settlements?period=&status=&page=&size=`（有值才传 period/status）
  - 详情：`GET /api/production/cost-settlements/{docNo}`
  - 建单：`POST /api/production/cost-settlements`（201；period=YYYYMM、remark 可空、行：workOrderDocNo + wipQty + wipCompletionPct，**不传料工费金额**）
  - 流转：`POST /{docNo}/approve|post|cancel`（200）；草稿→审核→过账 / 草稿作废
  - 工单须开工或已完工（EXECUTING / COMPLETED）；界面只展示后端归集结果（料按净领料、工费约当产量），不重算金额
  - 过账：CostAdjust 追加完工工费 + 出 GL；账期已关则整单回滚，错误展示 `{error}` 原文
- 数量/金额一律字符串传输；列表/详情展示时去掉小数末尾 0（`5.000000` → `5`），不改提交真源。
- 账期可手填 YYYYMM；登录即可 `GET /api/gl/periods` 点选已开账期。
- 工单号可手填；有 `production:wo` 时辅助点选开工/已完工工单（`GET /api/production/work-orders?status=EXECUTING|COMPLETED`），无该权限则只手填、不发工单列表请求。
- 写动作同步 in-flight 守卫；列表/详情/账期均有 request gate。

## 权限

| 动作 | 权限 | 前端 |
|---|---|---|
| 进入生产模块 | 任一 `production:*` | 侧边栏既有规则 |
| 成本结转查写 | `production:cost` | 无权限**隐藏**成本结转页签；组件内另有无权限空态，**不发任何** cost-settlements 请求 |
| 从列表点选开工/已完工工单 | 另需 `production:wo` | 无工单权限时只手工填工单号 |

会计账号若已有 `production:cost` 应可见本页签（对照第十刀：accountant 无 material/report 故隐藏那些页签——本刀按权限码本身控制）。后端仍是最终授权边界。

## 手工验收

1. 有 `production:cost`：能看结转列表详情，能建单（账期 + 工单行 + 在产数量/完工程度），能按状态审核/过账（草稿还可作废）。
2. 同时有 `production:wo`：可从开工或已完工工单列表点选。只有 cost、没有 wo → 页签仍在，工单号手填，不请求工单列表。
3. 无 `production:cost`（仍能进生产模块）→ 成本结转页签不可见；即使进入组件也明确提示且不调 `/api/production/cost-settlements`。
4. 完工程度 101、负数在产、重复工单：前端拦截；账期已关过账、工单未开工：展示后端 `{error}` 原文。
5. 详情展示料/工/费/完工/在产等后端字段，界面去尾零；创建请求不带金额。
6. 不改 `web-dist/`、`server/`。

## 未做

- Agent 工具 UI。
- 成本结转冲销（后端本批也不做 COMPLETED→REVERSED）。
- 制造费用参数维护页（读参数非必须，过账由领域服务使用已有参数）。

## 本地测法

```bash
cd web
npm test
npm run build
```

## 结果

- `cd web && npm test`：见本刀提交记录（含结转列表/创建窄 body/流转路径、账期与在产校验、权限 fail-closed、in-flight 守卫、CJK 文案）。
- `cd web && npm run build`：TypeScript 检查与 Vite production build。
- 未改 `web-dist/`、`server/`。
