# 传统 UI 第三刀：资金账户 / 应付台账 / 应收台账

## 当前状态

从 `main`（#50 库存工作台）派生。财务模块不再走 `ModulePlaceholder`，接入三页真实工作台；**不做凭证、账期、科目表维护、财务报表**，也不改预发 `web-dist` 静态包。

## 验收范围

- 财务模块页签：**资金账户**、**应付台账**、**应收台账**（应收页签仅 `sales:invoice` 可见）。
- 资金账户：`GET/POST /api/fund/accounts`、`GET/PUT /{id}`、`POST /{id}/enable|disable`；GL 下拉 `GET /api/gl/accounts?leafOnly=true`。查询登录即可，写操作 fail-closed `finance:payment_account`。
- 应付台账：仅 `GET /api/payables`（无 `GET /{id}`），详情取列表行；核销轨迹 `GET /api/settlements?type=PAYABLE&targetId=` 需 `finance:settlement`。
- 应收台账：`GET /api/receivables` + `GET /{id}`，未核销字段为 `openAmount`（与应付 `outstandingAmount` 不混用）；核销 `type=RECEIVABLE`。
- 金额一律字符串归一化；写动作（创建/更新/启停）同步 in-flight 守卫；列表/详情/核销/名称回查均有 request gate。
- 未接账龄报表 UI（`/api/reports/payable-aging`、`/api/reports/receivable-aging` 后端已有，本批未做）。
- 核销写动作仍走既有付款/收款单，本批只读台账 + 轨迹。

## 后端字段对齐

按同学确认契约实现，未猜字段名。应付无独立详情端点，已在详情空态与 PR 中点名。
