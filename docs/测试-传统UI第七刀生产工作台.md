# 传统 UI 第七刀：生产工作台（BOM + 工艺路线）

## 当前状态

从 `main`（财务第五刀账龄报表）派生。侧边栏「生产」从 `ModulePlaceholder` 换成真实 `ProductionWorkbench`；本刀只做 **BOM**、**工艺路线** 两页，对接 main 已有 `/api/production/boms` 与 `/api/production/routings`，**无 mock**。后续 SOP/DP、MRP、工单、领料、报工、成本结转页签本刀不占位、不露出。不改 `server/`，不改预发 `web-dist` 静态包。

## 验收范围

- 生产模块页签：**BOM**、**工艺路线**。查询登录即可（与后端 Controller 一致）；无 `production:bom` / `production:routing` 时 fail-closed 隐藏对应写按钮（新建/编辑/启停），页签策略对齐财务：只露本刀真实页，不放后续占位。
- BOM
  - 列表：`GET /api/production/boms?productId=&status=&page=&size=`（有值才传 productId/status）
  - 详情：`GET /api/production/boms/{id}`
  - 新建：`POST /api/production/boms`（默认启用，同产品其他启用版本由后端停用）
  - 整体更新行：`PUT /api/production/boms/{id}`（version 创建后只读，body 仍带回 productId/version）
  - 启停：`POST /{id}/enable`、`POST /{id}/disable`，启用前确认会停用同产品其他版本
  - 可选展开：`GET /api/production/boms/{productId}/explode?quantity=`（按父件 productId，不是 BOM id）
- 工艺路线
  - 列表：`GET /api/production/routings?productId=&status=&page=&size=`
  - 详情：`GET /api/production/routings/{id}`
  - 新建：`POST /api/production/routings`
  - 整体更新工序：`PUT /api/production/routings/{id}`
  - 启停：`POST /{id}/enable`、`POST /{id}/disable`
- 用量、损耗率、标准工时、费率一律字符串展示/提交；损耗率范围 `[0, 1)`，空则提交 `null`（领域默认 0）；工作中心/费率可空。
- 商品选择走真实 `GET /api/catalog/products`；单位走 `GET /api/catalog/units`。
- 写动作同步 in-flight 守卫；列表/详情/展开/单位均有 request gate。无 DELETE、无冲销入口。

## 权限

| 动作 | 权限 | 前端 |
|---|---|---|
| 进入生产模块 | 任一 `production:*` | 侧边栏既有规则 |
| 查 BOM / explode | 登录即可 | BOM 页签始终可见 |
| 新建/改行/启停 BOM | `production:bom` | 无权限隐藏写按钮 |
| 查工艺路线 | 登录即可 | 工艺路线页签始终可见 |
| 新建/改工序/启停工艺路线 | `production:routing` | 无权限隐藏写按钮 |

后端仍是最终授权边界。

## 手工验收

1. ADMIN/BOSS 登录 → 侧边栏生产 → 能看到 BOM / 工艺路线，能新建、改行、启停、展开。
2. 无 `production:bom` 的已登录用户（仍能进生产模块，例如仅有其他 production 权限）→ BOM 列表可查、无「新建 BOM / 编辑行 / 启停」。
3. 新建 BOM 后同产品旧启用版本应变为停用；启用另一版本前有确认文案。
4. 用量 `0`、损耗率 `1`、子件=父件、子件重复：前端拦截；环检测错误展示后端 `{error}`。
5. 工艺路线工序序号重复、工时 `0`：前端拦截；空工作中心/费率可保存。

## 结果

- `cd web && npm test`：见本批提交后输出。
- `cd web && npm run build`：TypeScript 检查与 Vite production build。
- 未改 `web-dist/`、`server/`。
