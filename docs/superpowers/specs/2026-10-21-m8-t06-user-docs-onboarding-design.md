# M8-T06 用户文档与引导

## 范围

- 中文上手指南与各模块操作手册（`docs/用户-*.md`）。
- 首次登录向导：建档案 → 期初 → 第一张采购订单草稿。
- 登录后「帮助」模块汇总步骤，可重新打开向导。
- 不改 `web-dist/`，不重开 M8-T01–T05，不新增 Flyway / 领域写路径。

## 向导

- 状态按用户名存在 `localStorage`（`pending` / `dismissed` / `completed`），不写业务库。
- 写步骤按当前权限裁剪；无权不展示提交。
- 调用已有 `createUnit` / `createWarehouse` / `createProduct` / `createSupplier` / `createCustomer`、`POST /api/inventory/adjustments`（OPENING）、`POST /api/purchase/orders`。
- 已有档案可下拉选用。名称、数量由用户填写，不预置演示假数据。
- 第一张单只建草稿，不自动审核过账。

## 帮助

- 新模块 `help`，登录即可见（与报表相同，无额外权限点）。
- 顶栏「新手引导」可强制再开向导。
