# 业务文档：进销存 Agent 工具（M3-T11）

> 面向业务与知识库。本文说明用户如何在**聊天界面**走完「采购→入库→应付」「销售→出库→应收」全链路，每个工具的业务含义与确认时机。技术注册见 `docs/领域工具清单.md`。

## 1. 一句话总览

M3-T11 把采购线与销售线的**全部单据动作**注册为 Agent 工具（共 20 个）：下单、审核、收/发货、过账、开票、查询。凡是**形成业务承诺、改动库存、产生应付/应收**的动作一律为高风险（HIGH），由框架强制弹出**确认卡片**，用户点确认后才执行——不会"一句话就把货发了"。查询类（NORMAL）随问随答，不打断。

## 2. 单据状态机（所有单据通用）

```
草稿(DRAFT) --审核--> 已审核(APPROVED) --过账--> 已完成(COMPLETED)
```

- **建单**只产生草稿，不动账、不动库存（采购订单=对供应商的承诺；销售订单=对客户的约定）。
- **审核**是受控关卡（职责分离：可由不同角色操作），审核后才能被下游单据引用。
- **过账**才真正落库存流水 / 生成应付应收，**不可逆**（冲销留 M4-T07 红字）。

正因如此，收一次货在聊天里是「审核采购单 → 建入库单 → 审核入库单 → 过账入库单」四个确认步骤——每一步都是一次明确授权，符合财务可审计原则。小企业一人多角时多点几次确认即可。

## 3. 采购链路（10 个工具）

| 阶段 | 工具 | 风险 | 业务含义 |
|---|---|---|---|
| 下单 | `create_purchase_order` | HIGH | 向供应商下采购单（草稿，不动库存）|
| 审核单 | `approve_purchase_order` | HIGH | 采购单审核通过，可被收货引用 |
| 收货建单 | `create_purchase_receipt` | HIGH | 引用已审核采购单，登记到货（草稿）；填收货仓 + 各行收货数量/可选收货单价 |
| 审核收货 | `approve_purchase_receipt` | HIGH | 入库单审核通过 |
| 过账收货 | `post_purchase_receipt` | HIGH | **真正入库**：产生 PURCHASE_IN 流水（移动加权成本），回写采购单到货量 |
| 开票建单 | `create_purchase_invoice` | HIGH | 引用已过账入库单登记发票（草稿）；三单匹配「开票量 ≤ 已收量」 |
| 审核发票 | `approve_purchase_invoice` | HIGH | 发票审核通过 |
| 过账发票 | `post_purchase_invoice` | HIGH | **生成应付账款**（按供应商结算方式推到期日）|
| 查收货 | `query_purchase_receipt` | NORMAL | 按 PR- 单号查入库单 |
| 查发票/应付 | `query_purchase_invoice` / `query_payables` | NORMAL | 查发票；按供应商/状态查应付台账 |

## 4. 销售链路（10 个工具）

| 阶段 | 工具 | 风险 | 业务含义 |
|---|---|---|---|
| 下单 | `create_sales_order` | HIGH | 给客户下销售单（草稿，不动库存；可用库存不足只提示不阻断）|
| 审核单 | `approve_sales_order` | HIGH | 销售单审核通过，可被出库引用 |
| 出库建单 | `create_sales_delivery` | HIGH | 引用已审核销售单，登记发货（草稿）；填出库仓 + 各行商品/发货数量 |
| 审核出库 | `approve_sales_delivery` | HIGH | 出库单审核通过 |
| 过账出库 | `post_sales_delivery` | HIGH | **真正出库**：产生 SALES_OUT 流水，按移动加权结转 COGS，回写发货量；**库存不足整批回滚** |
| 开票建单 | `create_sales_invoice` | HIGH | 引用已过账出库单登记发票（草稿）；客户从出库→订单链推导 |
| 审核发票 | `approve_sales_invoice` | HIGH | 发票审核通过 |
| 过账发票 | `post_sales_invoice` | HIGH | **生成应收账款**（OPEN，核销留 M4-T03）|
| 查出库 | `query_sales_delivery` | NORMAL | 按 SD- 单号查出库单（含 COGS）|
| 查发票/应收 | `query_sales_invoice` / `query_receivables` | NORMAL | 查发票；按客户/状态查应收台账 |

## 5. 典型对话脚本（采购收货）

> 用户：「给华东金属下 500kg 不锈钢板采购单，单价 12.5，然后收货入一号仓。」

Agent 会：
1. 复述要点 → 调 `create_purchase_order`（确认卡片）→ 得到 PO-202606-0001（草稿）。
2. 「采购单已建，需审核后才能收货，是否审核？」→ `approve_purchase_order`（确认）。
3. 复述收货要点 → `create_purchase_receipt`（引用 PO 行、一号仓、500kg）（确认）→ PR-202606-0001。
4. `approve_purchase_receipt` → `post_purchase_receipt`（确认）→ **库存 +500kg，加权成本入账**。
5. 回复入库结果，并提示「如需登记发票形成应付，可继续开票」。

每一步 Agent 都**先在正文复述再调用**，确认卡片由框架自动弹出；Agent 绝不自己再问一轮"是否确认"（避免双重确认），也绝不在过账成功前声称"已入库/已形成应付/已出库/已形成应收"或编造单号。

## 6. 权限（角色分工，详见 `docs/权限矩阵.md`）

- 采购员（PURCHASER）：`purchase:order/receipt/invoice` 全程。
- 仓管（WAREHOUSE）：`purchase:receipt`（收货）+ `sales:delivery`（发货）——管实物出入库。
- 会计（ACCOUNTANT）：`purchase:invoice` + `sales:invoice`——管开票与应付应收。
- 销售（SALES）：`sales:order/delivery/invoice` 全程。
- 老板/管理员（BOSS/ADMIN）：全量。
- 查询应付/应收（`query_payables` / `query_receivables`）登录即可，无权限点。

Agent 工具的权限校验是第二道防线（第一道是高风险确认卡片）：角色无权时工具直接返回拒绝，不执行。

## 7. 相关

- 库存与成本：`docs/业务-库存盘点.md`、`docs/业务-库存调拨.md`、`docs/M3拆解-库存与成本.md`
- 采购/销售单据规则：`docs/业务-采购.md`、`docs/业务-销售.md`
- 账实核对：`docs/业务-数据一致性校验.md`
- 期初建账：`docs/业务-期初导入.md`
