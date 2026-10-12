# 传统 UI 第五刀：应收/应付账龄 + 核销流水

## 当前状态

从 `main`（#52 凭证工作台）派生。财务模块新增只读「应收账龄」「应付账龄」页签；核销流水继续挂在应收/应付台账行上，**不提供全局核销列表、不提供 settle 写入口、不做凭证冲销/试算/关账**，也不改预发 `web-dist` 静态包。

## 验收范围

- 财务模块页签新增 **应付账龄**、**应收账龄**，仅 `finance:settlement` 可见；无该权限 fail-closed 隐藏。
- 应收账龄：`GET /api/reports/receivable-aging?asOf=&customerId=&page=&size=`；`asOf` 可空（服务端默认今天），客户可选。
- 应付账龄：`GET /api/reports/payable-aging?asOf=&supplierId=&page=&size=`，筛选口径对称。
- 响应金额一律当字符串展示；档案缺失时对手方编码/名称可空，前端回退「对手方 #id（名称不可用）」。
- `grandTotal` 为当前筛选全集（不是本页小计），页头单独展示。
- 核销流水复用台账详情：`GET /api/settlements?type=RECEIVABLE|PAYABLE&targetId=`，必须带 type + 子账主键；无 page/size、无日期过滤、无 POST。
