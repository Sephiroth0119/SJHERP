# SJHERP — 下一代 Agent 原生 ERP

## 项目愿景

面向**小型企业**的下一代 ERP 客户端，业务涵盖**进销存 + 生产 + 财务**。
核心差异：用户不再学习复杂的 ERP 操作，而是通过 **Agent 聊天界面**完成业务（也保留传统的业务入口直接点击）。Agent 在需要用户决策时返回**选项卡片**供点击，而不是让用户打字描述一切。

### 为什么做这件事（项目第一性原理）

传统企业很难落地 Agent ERP，根因是：数据模型破碎、垃圾数据多、流程不规范——本质上是因为旧 ERP 太复杂、上手门槛太高，导致使用者不愿意遵循规范。本项目的回答：

1. **把门槛降到聊天**，让规范流程成为最容易的路径，而不是最难的。
2. **流程缺口自进化闭环**：用户遇到系统做不到的事 → 向 Agent 提出 → Agent 单开一条线记录缺口、提交开发 issue → 开发者 Agent 识别后自动开发补齐 → 热部署上线 → 该业务知识写入**系统大记忆**，供后续统计/查询复用。
3. **永远严格保持业务数据模型不破碎**——这是全项目最高优先级的不可妥协原则。

## 不可妥协的原则（任何代码改动都不得违反）

1. **数据模型完整性高于一切**。Agent、热部署的扩展、临时需求，都不允许绕过领域模型直接写库。所有写操作必须经过领域服务层，受单据状态机和校验规则约束。宁可拒绝一个需求，不可破坏一次模型。
2. **财务数据专业性**。结算单、存货核算、库存数据等财务产出必须符合会计逻辑：借贷平衡、成本核算方法一致（移动加权/先进先出，全局统一配置）、期间不可随意重开。财务记录**只可冲销、不可物理修改/删除**。
3. **可审计**。每一笔业务数据变更必须有审计日志：谁（用户/哪个 Agent）、何时、依据什么指令、改了什么。Agent 自动执行的操作尤其要可追溯。
4. **数据一致性持续校验**。独立的**检查 Agent** 定时分析最新数据是否符合业务逻辑（库存账实、应收应付与单据勾稽、工单领料/完工与存货成本勾稽、总账与明细账平衡等），发现错误立刻上报，不静默修复。
5. **金额与数量精度**：金额一律 `BigDecimal`（数据库 `DECIMAL`），禁止 float/double 参与任何金额/数量/成本运算。

## 技术栈（已定）

| 层 | 选型 | 说明 |
|---|---|---|
| 后端 | Java 21 + Spring Boot 3.x，Maven | 业务核心 |
| Agent 框架 | **完全自研**（本项目核心资产之一） | 不引入 LangGraph/Spring AI 编排，详见下文设计要点 |
| 前端 | React + TypeScript（Vite） | 聊天界面为主入口 + 传统业务入口 |
| 业务数据库 | MySQL 8.x | 所有进销存/财务数据，InnoDB，强事务 |
| 向量库（大记忆） | 独立向量库（候选 Qdrant，轻量易部署；待定） | 系统大记忆：业务知识、流程缺口解决方案、统计口径 |
| LLM 接入 | 自建抽象层，可切换 | 业务代码只依赖统一接口；DeepSeek/通义/Claude/GPT 可配置切换；检查 Agent、开发者 Agent 等关键环节配强模型 |

## 仓库结构（规划）

```
SJHERP/
├── CLAUDE.md
├── docs/                  # 架构决策(ADR)、领域模型文档、会计核算规则
├── server/                # Java 后端（Maven 多模块）
│   ├── sjherp-agent/      # 自研 Agent 框架（领域无关，可独立演进）
│   ├── sjherp-domain/     # 领域模型与领域服务（进销存+财务核心）
│   ├── sjherp-app/        # 应用层：Agent 工具(Tool)定义、API、编排入口
│   └── sjherp-infra/      # 持久化、LLM 抽象层实现、向量库客户端
└── web/                   # React + TS 前端
```

> 注意：以上为规划。实际创建模块时遵循此结构；若需调整，先更新本文件和 docs/ 中的 ADR。

## 自研 Agent 框架设计要点

- **状态显式持久化**：Agent 会话状态存数据库，进程重启/热部署后可恢复，不依赖内存。
- **工具（Tool）即领域服务**：Agent 能做的所有业务操作都是注册的领域服务方法，带参数校验与权限。Agent 没有"裸 SQL"或自由写库的工具。
- **选项返回协议**：Agent 回复是结构化消息（文本 + 可选项数组 + 可选表单），前端将选项渲染为可点击卡片。这是前后端的核心契约，定义在 docs/ 中并保持版本化。
- **Human-in-the-loop**：涉及资金、过账、期间关账等高风险操作，Agent 必须返回确认选项，由人点击确认后才执行。
- **流程缺口通道**：Agent 判断"当前能力做不到"时，走专门的缺口记录流程（结构化记录场景、期望、缺失能力）→ 生成开发 issue → 写入大记忆。不允许 Agent 在能力不足时自由发挥绕过模型硬做。
- **检查 Agent**：独立调度（定时任务），输出结构化的不一致报告。

## 业务范围

- **进**：采购订单 → 入库 → 应付 → 付款
- **销**：销售订单 → 出库 → 应收 → 收款
- **存**：库存台账、盘点、调拨、存货成本核算
- **产**（生产模块）：
  - **计划**：SOP（销售与运作计划）/ DP（需求计划）→ 依据 BOM 展开生成生产建议与采购建议
  - **执行**：WO 生产任务单（工单）→ JIT 领料/齐套检查 → 报工 → 完工入库
  - **基础数据**：BOM（物料清单）、工艺路线（简化版，小企业够用即可）
  - **成本**：工单维度归集料工费，完工结转至存货成本，与财务模块勾稽
- **财务**：总账、应收应付、结算单、期间结账、基础报表

单据皆为状态机（草稿 → 审核 → 执行 → 完成/冲销），状态流转规则定义在领域层，文档化在 docs/。

## 开发约定

- **语言**：代码标识符用英文；注释、文档、commit message、用户可见文案用中文。
- **Git**：commit message 用中文，格式 `类型: 简述`（类型：feat/fix/refactor/docs/test/chore）。主分支 `main`，功能分支 `feat/xxx`。
- **测试**：领域层（尤其库存成本核算、财务勾稽逻辑）必须有单元测试；金额计算类测试覆盖边界（四舍五入、负数、冲销）。
- **禁止事项**：
  - 不写裸 SQL 绕过领域服务做业务写操作（报表只读查询除外）。
  - 不在 Agent prompt 里硬编码业务规则——业务规则在领域层代码中，Agent 通过工具调用获得。
  - 不引入未在本文件登记的重量级框架/中间件，先讨论并记 ADR。
- **Subagent 派工模型分级**（详见路线图 §13.6）：模式复制类任务用 sonnet、复杂逻辑与交叉校验用 opus、编排与决策由主模型把控；质量闸门（测试+CI+校验）不随模型降档。
- **API 调试**：dev/local profile 下开放 API 文档（生产强制关闭）。**需鉴权的接口调试用 `/swagger-ui/index.html`**——点 Authorize 填 token 后 Execute 自动带 `Authorization: Bearer <token>`，最省事；`/doc.html`（knife4j）界面更友好可浏览，但因 knife4j 处于纯静态 UI 模式（见下），其「调试」面板的全局 Authorize **不会注入鉴权头**，需在「调试 → 请求头部」手填 `Authorization`（带不带 `Bearer ` 均可——JwtAuthenticationFilter 已容忍可选前缀，仍全量验签）。
  登记依赖：`springdoc-openapi-starter-webmvc-ui:2.7.0`（/v3/api-docs 生成 + 标准 swagger-ui，兼容 Spring 6.2.x）+ `knife4j-openapi3-ui:4.5.0`（仅前端静态资源，不引入其 autoconfigure；knife4j-openapi3-jakarta-spring-boot-starter 的 Spring autoconfigure 与 springdoc 2.4+ 二进制不兼容，故弃用——代价是 knife4j 增强特性[含调试面板全局鉴权注入、knife4j.enable 等]失效，鉴权调试以 swagger-ui 为准）。

## 当前状态

底座 v0.1 + M1/M2 主体已完成：自研 Agent 框架具备工具调用执行循环与高风险框架级确认拦截（HITL）、流程缺口记录通道、调用观测（token/耗时落库）；业务侧有商品/客户/供应商/仓库档案（领域服务+REST+Agent Tools）、通用单据状态机与编号规则、JWT 认证与角色、角色权限模型双层生效（Agent 工具 + REST，矩阵见 docs/权限矩阵.md）、统一审计切面（audit_log，人工/Agent 可区分）、会话历史滚动摘要、CI 门禁（含 Testcontainers DB 集成测试 job）。**M1 全部完成**；**M2 全部完成**（T09 期初导入已交付：POI Excel 导入档案+期初库存经唯一入口、逐行校验报错、全有或全无；期初应收应付按数据模型红线暂缓到 M4）。**M3 进销存全部完成 T01–T13**：库存线 T01-T04（台账/移动加权成本/盘点/调拨，唯一写入口+对账恒等式+并发一致性）、采购线 T05-T07（订单→入库 PURCHASE_IN→发票→应付）、销售线 T08-T10（订单→出库 SALES_OUT 含 COGS 移动加权→发票→应收）、**T11 进销存 Agent 工具全量（采购/销售各 10 个：下单/审核/收发货/过账/开票/查询，建单·审核·过账各独立 HIGH 确认卡片，常驻工具 40 个）**、**T13 数据一致性校验（ConsistencyCheckService 7 类勾稽 + REST + run_consistency_check 工具 + @Scheduled 检查 Agent + 端到端 PurchaseToSalesFlowIntegrationTest 真库验收 0 ERROR break）**、**T12 进销存报表（4 张只读报表 /api/reports/*：库存余额表/收发存汇总[tie-out 期初+收−发=期末]/采购入库明细/销售出库明细[销售额·COGS·毛利]；主表一律 LEFT JOIN 防财务报表静默丢行、金额 BigDecimal 字符串、ReportQueryIntegrationTest 真库 tie-out 验收）**。单据均走状态机+审计；应付/应收台账已生成（核销/账龄 M4-T03 已完成）；跨发票超额开票防控已修。**M4 财务核心开篇——M4-T01 总账基建已完成**（com.sjherp.domain.gl）：会计科目表（小企业会计准则预置走 V19 迁移，is_preset 守护）、记账凭证 Voucher（继承 BusinessDocument，DRAFT→APPROVED 一步过账，beforeTransition 白名单兜底"只可冲销"；**保存即校验借贷平衡 Σ借=Σ贷、不平无法保存**）、账期管理（OPEN/CLOSED，**关账期过账抛 PeriodClosedException 被拒**）、试算平衡/科目余额派生（仅统计已过账凭证，维护型余额表留 T05）；V19 四表 + /api/gl/** REST + finance:*(account/period/period_reopen/voucher) 权限（科目/账期查询登录即可、凭证全程受控）+ @Audited 全覆盖；两条验收真库 GeneralLedgerPostingIntegrationTest 通过；设计真源 docs/M4拆解-总账基建.md。**M4-T02 业务→凭证自动化已完成**：凭证模板引擎 AutoVoucherService 在 M3 四个采购/销售过账事件**同事务内自动生成并过账凭证**（入库 借1405/贷220201 暂估应付、采购发票 借220201/贷220202 正式应付、出库 借6401/贷1405 COGS、销售发票 借1122/贷6001）；**暂估入账模型**（V20 拆 2202→220201 暂估/220202 正式）解决货到票未到、使存货/应付与子账时点勾稽；**幂等**（source_doc_no 回填 + findBySourceDocNo 查重 + V20 uk_voucher_source 唯一约束）保证每单恰一组、重过账不重复；账期缺期自动 open / CLOSED 经 post 抛 PeriodClosedException 回滚；无新增权限点（自动凭证由业务过账权限驱动）；设计真源 docs/M4拆解-业务凭证自动化.md。**M4-T03 应收应付核销/账龄已完成**：核销引擎（新 com.sjherp.domain.settlement）——AccountsReceivable/Payable.settle（部分核销累加、OPEN→PARTIAL→SETTLED、超额抛 OverSettlementException 硬拒）+ **只追加** SettlementRecord（子账 settled_amount 是其维护型 rollup、记录表才是真源，原则 2/3）+ SettlementService（@Audited，装载→settle→save 子账 UPDATE→落记录）；V21 settlement_record 表（payment_doc_no 留 T04 回填/T07 红冲锚点）；JdbcAccountsPayableRepository 补核销 UPDATE 路径+findById。账龄分析（app.finance AgingReportDao 只读，按对手方 5 桶[未到期/逾期1-30/31-60/61-90/90+ CASE SUM 未核销余额]+grandTotal，**主表 LEFT JOIN 不静默丢行**）GET /api/reports/receivable-aging|payable-aging + 核销历史 GET /api/settlements；新增 finance:settlement 权限（ADMIN/BOSS/ACCOUNTANT，本批仅护只读）。**关键裁定（T03/T04/T07 切分）**：减少应收/应付与收到现金是同一经济业务（借银行/贷应收），脱离收付款的独立核销即记账错误→核销引擎本批**不出 GL、不暴露独立 settle 写入口、无生产触发器**（仅 M4-T04 收付款单同事务触发，届时配现金侧凭证+银行账户档案），冲销留 T07；故 T03 上线**无"动子账不动 GL"路径**。真库 SettlementEngineIntegrationTest（核销状态落库/记录追溯/超额拒绝 DB 态不变/findByPaymentDocNo）+ AgingReportIntegrationTest（5 桶 tie-out+边界临界 0/1/30/31/90/91+全结清剔除+档案缺失 LEFT JOIN 不丢行）验收；设计真源 docs/M4拆解-应收应付核销账龄.md。**1032 后端测试全绿**（M4-T01 三镜头 0 P0、1 P1 已修[凭证状态流转白名单]；M4-T02 三镜头 0 P0、P1 系误报、P2/P3 已修[出库凭证日改过账日 now(UTC)、账期并发 open 改回滚可重试]；M4-T03 四镜头 0 P0/P1，P2[findByPaymentDocNo 真库覆盖]+4 P3[V21 tenant_id 对齐全库 BIGINT、账龄 WHERE 加余额>0 兜底、桶边界样本、超额回滚注释据实改]已修，AP 核销 UPDATE 未带 tenant_id 登记 ADR-002 多租户统一收口）。业务文档见 docs/业务-*.md。**M4-T04 收付款单开工（L 任务拆 a/b/c）——T04a 现金/银行账户档案已完成**：照 warehouse 范式新建 com.sjherp.domain.fund（PaymentAccount，FA- 编号，glAccountCode 校验须末级+启用 GL 货币科目）+ V22 + /api/fund/accounts + finance:payment_account 权限 + 2 Agent 工具（create/search NORMAL）；**1090 后端测试全绿**；二镜头校验 0 P0/P1，1 命名 P2 已修；设计真源 docs/M4拆解-收付款单.md。下一步：M4-T04b 收付款单（收/付款单 header+分摊行，过账同事务联动 T03 核销引擎+现金侧凭证 借银行/贷应收·借应付/贷银行）、M4-T04c（Agent HITL 工具+核销 rollup 勾稽）、M4-T05 月末结转关账、M4-T07 统一冲销机制、M4-T08 财务 Agent 工具；T02 后续小批补期初/盘点/成本调整凭证。遗留 P2（已登记路线图 §0.5，非阻塞）：三单校验短路漏报、财务台账反向孤儿盲区（与 M4 核销同期）、一致性聚合未含 batch_id、导入空文件健壮性、报表 keyword LIKE 索引/ESCAPE 与毛利·库存总值权限敏感度（与库存查询同既有模式）。**M3 全部完成 + M4 地基（T01 总账 + T02 业务自动凭证 + T03 应收应付核销/账龄）就位，向 v0.5 beta（M3+M4 财务核心）推进中**。

0→1 完整计划见 **docs/产品路线图-0到1.md**（任务编号制，分配 subagent 时引用编号；该文档是计划的单一真源）。当前推进位置与已知技术债以该文档为准。
