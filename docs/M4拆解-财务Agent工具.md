# M4-T08 财务 Agent 工具 拆解与实现方案

> 路线图 §6 M4-T08（M 级，**M4 财务核心收尾**）。本文是 T08 单一设计真源。
> 范围：财务**只读查询** Agent 工具（查应收/账龄/利润/资产负债表/试算/核销历史）。发起收付款（确认卡片）与月末关账引导已在 T04c/T05 落地，本批不重复。
> 验收：聊天可查账龄/利润表/资产负债表/试算平衡/核销历史，权限与 REST 同口径。

## 0. 去重盘点（Explore 确认，勿重复造）
**已存在、本批不做**：query_receivables/query_payables（应收应付列表，M3-T11）、收付款建·审·过账·查询（T04c）、precheck_period_close/close_accounting_period（T05，月末关账引导）、reverse_*（T07）。
**本批新增 8 个只读查询工具**（NORMAL）：账龄×2 + 资产负债表/利润表×2 + 试算平衡/科目余额×2 + 核销历史×2。

## 1. 工具清单（全 NORMAL 只读；权限对齐对应 REST 端点口径——Agent 工具与 REST 共用权限矩阵，docs/权限矩阵.md）

| 工具 name | 调用服务（已存在） | 入参 | 权限点（对齐 REST） |
|---|---|---|---|
| `query_receivable_aging` | `AgingReportDao.receivableAging(asOf, customerId, page, size)` | asOf(YYYY-MM-DD,缺省今天)、customerId(可选)、page/size(可选) | `finance:settlement`（同 AgingReportController） |
| `query_payable_aging` | `AgingReportDao.payableAging(asOf, supplierId, page, size)` | asOf、supplierId(可选)、page/size | `finance:settlement` |
| `query_balance_sheet` | `FinancialStatementService.balanceSheet(period)` | period(yyyyMM,必填) | `finance:report`（同 FinancialStatementController） |
| `query_income_statement` | `FinancialStatementService.incomeStatement(period)` | period(yyyyMM,必填) | `finance:report` |
| `query_trial_balance` | `VoucherAppService.trialBalance(period)` | period(yyyyMM,必填) | `finance:voucher`（同 GlVoucherController /trial-balance） |
| `query_account_balance` | `VoucherAppService.accountBalance(accountCode, period)` | accountCode(必填)、period(必填) | `finance:voucher` |
| `query_receivable_settlements` | `SettlementReadAppService.findReceivableSettlements(receivableId)` | receivableId(必填) | `finance:settlement`（同 /api/settlements） |
| `query_payable_settlements` | `SettlementReadAppService.findPayableSettlements(payableId)` | payableId(必填) | `finance:settlement` |

**权限裁定**：只读财务工具**带 requiredPermission**（非 null），与对应 REST 端点同权限点——财务报表/账龄/核销/凭证是敏感经营数据（与 precheck_period_close 的 NORMAL+finance:period 先例一致：只读但受控）。区别于 query_receivables/库存查询等「登录即可」的台账/档案查询。

## 2. 实现（com.sjherp.app.tool.gl 或 .finance，照 QueryInventoryBalanceTool/PrecheckPeriodCloseTool 范式）
- 每工具 implements Tool：name()（snake_case）、description()（面向 LLM：何时用/查什么/返回什么）、parameterSchema()（JSON Schema draft 2020-12，period 校验 `^[0-9]{6}$`、asOf 校验 `^[0-9]{4}-[0-9]{2}-[0-9]{2}$`）、riskLevel() 默认 NORMAL（不覆写）、requiredPermission() 返回上表权限点、execute() 调服务 → 构造 `LinkedHashMap` 有序结构 → `ToolResult.ok(data)`。
- **精度铁律**：金额一律 `BigDecimal.toPlainString()`（绝不用 JSON 数字）；日期 ISO 字符串；分页 page/size/total 整数。
- 异常映射 `ToolResult.fail`（NotFound/IllegalArgument）；参数缺失前置 fail 不调服务。
- 注入：注册时由 DomainToolConfig 提供 AgingReportDao/FinancialStatementService/VoucherAppService/SettlementReadAppService（均已 @Service/@Repository 或可装配）。
- DomainToolConfig 注册 8 工具 + 更新计数注释（常驻 63→71，全 NORMAL）；HighRiskToolPermissionTest 规模基线 ≥63→≥71（HIGH 工具数不变，仅总数+8；NORMAL 带权限点不触发"HIGH 必须有权限点"断言）。

## 3. 测试
- 8 工具单测（照既有 query 工具测试范式）：每工具断言 name/riskLevel=NORMAL/requiredPermission（上表）/parameterSchema 合法（必填项、pattern）/execute 调对应服务（mock，verify 参数透传）/返回结构含关键字段（金额字符串）/异常映射 fail/参数缺失前置 fail 不调服务。
- DomainToolConfig 计数 + HighRiskToolPermissionTest 基线同步。
- 无新增集成测试（只读封装，底层服务已有真库集成覆盖：AgingReportIntegrationTest/FinancialStatementFlowIntegrationTest/GeneralLedgerPostingIntegrationTest/SettlementEngineIntegrationTest）。

## 4. 风险与决策
1. **零新增领域逻辑/迁移/权限点**：纯封装已有只读服务，复用既有 finance:settlement/report/voucher 权限点。
2. 权限对齐 REST 口径（§1），Agent 与 REST 同权限矩阵不分叉。
3. account_balance 独立工具（不并入 trial_balance）：单科目+账期查询是高频独立诉求。
4. 文档同步：docs/领域工具清单.md +8 工具、LlmAgent 系统提示词「业务能力」段 + 财务报表/账龄/核销查询能力、docs/权限矩阵.md（工具行）。
5. 全程只读、BigDecimal 字符串、@Audited 不需要（只读无审计）。
6. **已登记小遗留（P3，非阻塞）**：账龄空桶/无行 grandTotal 兜底用 `BigDecimal.ZERO.toPlainString()` 输出 `"0"` 而非 `"0.00"`（DB SUM 出来的桶值带 DECIMAL scale 为 `"0.00"`，仅纯零兜底处不统一）；展示无歧义，待后续统一以 `setScale(2)` 规整。
7. **计数口径**：DomainToolConfig 常驻 register() = 69（查询 29 NORMAL + 建档 1 NORMAL + 写 39 HIGH）；DemoToolConfig dev-only +2 → 全量注册 71，HighRiskToolPermissionTest 断言基线 `>= 71`（全量口径）。
