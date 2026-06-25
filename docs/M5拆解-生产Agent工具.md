# M5-T07 生产 Agent Tools 拆解与实现方案

> 路线图 §7 M5-T07（M 级）。本文是 T07 单一设计真源。
> 范围：把 M5-T01~T06 已建好的生产领域/应用服务封装为自研 Agent 框架 Tool（聊天界面调用）；写动作走框架级 HITL 确认卡片，查询 NORMAL。**零新增权限点、零新增领域逻辑/迁移**——纯封装。

## 0. 范围红线 + 关键事实
- **做**：26 个生产工具（工单/领料退料齐套/报工/成本结转/MRP 查询），新建 `ProductionToolConfig`（独立装配，不塞已临界爆炸的 DomainToolConfig）+ `ProductionToolSupport`（名称→id 解析 + decimal/int/date 工具，照 PurchaseToolSupport）。
- **不做**：BOM/Routing 维护工具、run_mrp 触发、需求计划建单、单据草稿 cancel（主数据/批处理/低价值，与 M3/M4 一致裁掉）；无新权限点、无新迁移、无新领域逻辑。
- **关键事实**：权限点 production:bom/routing/plan/mrp/wo/material/report/cost 全就绪，工具直接复用与 REST 同口径。每工具实现 `com.sjherp.agent.tool.Tool`（NAME 常量/description/parameterSchema JSON Schema 字符串/riskLevel/requiredPermission/execute 调 AppService + ToolResult.ok/fail + 异常映射）；操作人 operator(context) 记 `agent:<userId>`；数量/金额/工时/百分比一律 string 承载、商品/仓库传名称或编码工具内解析。封装严格照 REST 控制器注入的 AppService 与方法签名，绝不绕过唯一写入口。

## 1. 工具清单（26 个：HIGH 19 / NORMAL 7）
### A. 工单 WorkOrder（封装 TransactionalWorkOrderService）— 8
| name | 级别 | 权限 | 封装 |
|---|---|---|---|
| create_work_order | HIGH | production:wo | createManual(...) |
| create_work_order_from_mrp | HIGH | production:wo | createFromSuggestion(mrpRunDocNo, productId, op) |
| release_work_order | HIGH | production:wo | release（下达 DRAFT→APPROVED） |
| start_work_order | HIGH | production:wo | start（开工 APPROVED→EXECUTING） |
| complete_work_order | HIGH | production:wo | complete（EXECUTING→COMPLETED） |
| cancel_work_order | HIGH | production:wo | cancel（DRAFT→CANCELLED） |
| reverse_work_order | HIGH | production:wo | reverse（APPROVED→REVERSED 冲销） |
| query_work_order | NORMAL | production:wo | get(docNo) / search（有 doc_no 走单查否则列表，查进度合一 D-3） |

### B. 领料/退料/齐套（T04）— 8（领退对称各 3 写动作 D-2）
| create_material_issue / approve_material_issue / post_material_issue | HIGH | production:material | MaterialIssueAppService.create/approve/post |
| query_material_issue | NORMAL | production:material | get/search |
| create_material_return / approve_material_return / post_material_return | HIGH | production:material | MaterialReturnAppService.create/approve/post |
| check_kitting | NORMAL | production:material | KittingCheckAppService.check（只读齐套缺料清单） |

### C. 报工完工入库（T05，封装 ProductionReportAppService）— 4
create_production_report / approve_production_report / post_production_report（HIGH）+ query_production_report（NORMAL）。

### D. 成本结转（T06，封装 ProductionCostSettlementAppService）— 4
create_cost_settlement / approve_cost_settlement / post_cost_settlement（HIGH，post 动成本+GL，账期 CLOSED 抛 PeriodClosedException 回滚）+ query_cost_settlement（NORMAL）。

### E. 计划查询（T02）— 1
query_mrp_run（NORMAL，production:mrp，查 MRP 建议供 create_work_order_from_mrp 取参）。

> 实现前确认退料状态机（DRAFT→APPROVED→post）：若 approve 非 post 前置必经态则 approve_material_return 仍保留（与领料对称、忠于状态机）。

## 2. HIGH vs NORMAL 判据（沿用 M3/M4 先例）
动库存/成本/GL/状态机写动作 → HIGH（框架 HITL 确认卡片，建·审·过账各独立 HIGH 忠于职责分离）；只读查询 → NORMAL。create_* 草稿虽不立即动库存但形成业务单据，判 HIGH（同 create_purchase_order）；check_kitting/query_* 只读 NORMAL。

## 3. 参数 schema（写动作要点，照 CreatePurchaseOrderTool/CreateTransferTool 多行范式）
- 统一：数量/金额/工时/百分比 string；商品/仓库传名称或编码（execute 内解析为 id，歧义返候选）；单据号字符串；additionalProperties:false；多行用嵌套 items object + 严格 required。
- create_work_order：product(必填)/planned_qty(必填)/unit·bom_version·routing_version·warehouse·planned_start/end_date(可选,缺省取启用版/基本单位)/remark。
- create_material_issue：work_order_doc_no/warehouse/lines[{product,required_qty?,quantity,unit?}]。
- create_material_return：material_issue_doc_no/warehouse/lines[{product,quantity,unit?,src_issue_line_no?}]。
- create_production_report：work_order_doc_no/warehouse/product/completed_qty/scrap_qty?/unit?/lines[{operation_seq_no?,operation_name?,work_center?,reported_hours,reported_qty?,unit?}]。
- create_cost_settlement：period(^[0-9]{6}$)/lines[{work_order_doc_no,wip_qty?,wip_completion_pct?}]——**工具绝不传料工费金额**（领域 Service 计算）。
- 状态流转/查询：doc_no（query_work_order 另支持 product+status+page+size 走 search）；check_kitting：work_order_doc_no+warehouse。

## 4. 注册与计数
- 新建 `ProductionToolConfig`（@Configuration，构造注入 ToolRegistry + 各生产 AppService + ProductService/WarehouseService/UnitService），构造内 register 全部 26 个，分段注释。
- 常驻 69→**95**（+26）；全量（含 dev 演示 2）71→**97**。
- HighRiskToolPermissionTest：registryWithAllTools() 追加 new ProductionToolConfig(mock...)；基线 ≥71→**≥97**；HIGH 数 +19；更新注释清单（M5-T07 段 19 HIGH+7 NORMAL）；「HIGH 必须声明非空权限点」断言自动覆盖。

## 5. 测试
- 每工具单测（照 ApprovePurchaseOrderToolTest/QueryBalanceSheetToolTest）：缺必填→fail 不调 AppService；名称解析失败/歧义→fail/候选；execute mock AppService verify 委托正确、ok 返回金额数量字符串；异常映射（WorkOrderNotFound/MaterialIssueNotFound/IllegalStateTransition/PeriodClosed/IAE→对应 fail 文案）；riskLevel/requiredPermission/name 防漂移断言。
- 权限对齐：HighRiskToolPermissionTest 计数基线 ≥97 + 每生产工具 requiredPermission ∈ production:* 断言；与 REST 端点权限点一致。
- 可选集成测：建单→下达→开工→领料建审过→报工建审过→成本结转 工具链 happy path 委托真实 AppService（时间紧可只单测+计数）。

## 6. 文档同步（强制）
docs/领域工具清单.md（+26 行）、docs/权限矩阵.md（工具行 +26，权限点 production:*）、LlmAgent.java 系统提示词（「当前业务能力」+生产全链路、SYSTEM_PROMPT_WITH_TOOLS +生产段：工单状态机/建审过三步 HIGH 确认/check_kitting 只读/过账成功前不得声称已领料已入库已结转/成本结转关账期内被拒）、本设计文档。

## 7. 关键裁定 + 风险
- D-1 注册 26（裁 BOM/Routing 维护、run_mrp、需求计划建单、草稿 cancel；含领退对称多出的 query_material_return）。D-2 领退对称（approve_material_return 保留）。D-3 query_work_order 单查列表合一。D-4 工单状态流转全 HIGH。D-5 零新增权限点。D-6 独立 ProductionToolConfig。
- R-1 DomainToolConfig 旧 HIGH 计数注释漂移（41 vs 39）遗留不修，本期新增段数字务必准。R-2 post_cost_settlement 账期 CLOSED→PeriodClosedException 文案「账期已关闭，无法结转」。R-3 ProductionToolSupport 统一解析避免口径不一。R-4 多行参数 LLM 易漏字段→description 写清每行必填 + schema 严格 required + 逐行早失败给清晰 error。R-5 create_work_order 可选项默认值（不传用启用 BOM/工艺版本）description 说明。
