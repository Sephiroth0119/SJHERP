# M5-T07 生产 Agent Tools 测试文档

> 配套设计真源 docs/M5拆解-生产Agent工具.md。
> T07 是纯封装任务（零新增领域逻辑/迁移/权限点），测试聚焦**工具层契约**：风险级别/权限点防漂移、缺参早失败不触碰服务、operator 记 `agent:<userId>`、AppService 委托正确、异常→`ToolResult.fail` 映射、金额/数量字符串承载。底层领域/库存/GL 正确性由 T03~T06 既有真库集成测覆盖，T07 不重复。
> 测试金字塔：本任务全部为 app 层工具单测（mock AppService）+ 注册计数断言（HighRiskToolPermissionTest）。

## 0. 验收红线对照
| 验收要求 | 覆盖测试 |
|---|---|
| 19 个写动作全 HIGH（框架 HITL 确认卡片），7 个查询/齐套 NORMAL | 各 `*ToolsTest` 的 `*_风险级别HIGH/NORMAL_权限点*` 断言（26 工具逐个） |
| 权限点 ∈ production:{wo,material,report,cost,mrp}，与 REST 同口径 | 各工具 `requiredPermission()` 断言 + `HighRiskToolPermissionTest` 计数/权限基线 |
| 成本结转工具**绝不接收料工费金额**（领域 Service 计算） | `CostSettlementToolsTest` create 仅传 period/wip_qty/wip_completion_pct，无金额入参 |
| operator 记 `agent:<userId>` 服务端派生（不可被 LLM 伪造） | 各 create/approve/post `*_operator记agent前缀_返回成功`（verify `eq("agent:42")`） |
| 缺必填参数早失败、不触碰 AppService | `*_缺失_失败且不触碰服务`（`verifyNoInteractions(service)`） |
| 领域异常映射为 fail 文案（NotFound/IllegalStateTransition/InsufficientStock/PeriodClosed） | 各 `*_不存在_转fail`、`post_库存不足_转fail`、`post_状态流转拒绝_转fail` |
| 唯一写入口不被绕过（工具只委托 AppService，无 SQL/无自算金额） | 全部工具经 mock AppService verify 委托；无 JdbcTemplate 依赖 |
| 注册计数：常驻 69→95（+26），全量 97 | `HighRiskToolPermissionTest` 基线 `>= 97`、HIGH 数不变断言 |

## 1. 工具单测（7 文件 / 85 例）
| 测试文件 | 例数 | 覆盖工具 | 要点 |
|---|---|---|---|
| `WorkOrderToolsTest` | 23 | 8（create/create_from_mrp/release/start/complete/cancel/reverse/query） | 状态流转全 HIGH；query NORMAL 单查/列表合一；WorkOrderNotFound/IllegalStateTransition→fail |
| `MaterialIssueToolsTest` | 15 | 4（create/approve/post/query） | post 库存不足 InsufficientStockException→「库存不足」；多行 lines 校验 |
| `MaterialReturnToolsTest` | 12 | 4（create/approve/post/query） | 领退对称；create 需 material_issue_doc_no |
| `ProductionReportToolsTest` | 13 | 4（create/approve/post/query） | create 需 work_order_doc_no+completed_qty；多行工时 lines |
| `CostSettlementToolsTest` | 14 | 4（create/approve/post/query） | **工具不传料工费金额**；post 账期 CLOSED→PeriodClosedException 文案 |
| `CheckKittingToolTest` | 4 | 1（check_kitting，只读 NORMAL） | 齐套缺料清单；work_order_doc_no+warehouse 必填 |
| `QueryMrpRunToolTest` | 4 | 1（query_mrp_run，只读 NORMAL，production:mrp） | 单查含建议明细 / 无 doc_no 分页历史 |

每文件统一三类断言：
1. **防漂移**：`riskLevel()`/`requiredPermission()`/name 常量。
2. **缺参早失败**：缺必填 → `result.success()==false` + error 含字段名 + `verifyNoInteractions(service)`。
3. **委托正确 + operator**：mock AppService，`tool.execute(args, context)` 后 `verify(service).方法(eq(...), eq("agent:42"))`，返回 data 含 doc_no/金额数量字符串。
4. **异常映射**：thenThrow 领域异常 → `result.success()==false` + 文案断言。

> 嵌套打桩坑（已固化）：mock 实体（getDocNo/getStatus/totalIssuedCost…）在 `@BeforeEach` 先建桩存字段（如 `issueStub = mockIssue()`），避免 `when(service.x()).thenReturn(mockXxx())` 把内层 `when()` 嵌进外层 thenReturn 触发 Mockito `UnfinishedStubbing`。

## 2. 注册与权限基线 `HighRiskToolPermissionTest`
- `registryWithAllTools()` 追加 `new ProductionToolConfig(mock 各 AppService + ProductService/WarehouseService/UnitService)`。
- 计数基线：常驻 95（69 + 26 生产）+ dev 演示 2 = **全量 ≥ 97**（断言 `>= 97`）。
- HIGH 工具必须声明非空 requiredPermission（既有断言自动覆盖新增 19 个 HIGH）。
- 每生产工具 requiredPermission ∈ `production:*`，与 REST 控制器 `@PreAuthorize` 同口径。

## 3. 评审修复在测试/文档中的固化（opus 单镜头对抗校验）
**0 P0/P1（可合入）。** 全部 19 写工具正确 HIGH、7 查询 NORMAL；成本工具不收金额；operator 服务端派生；异常映射/退料 approve 对称均正确。
| 项 | 级别 | 处置 |
|---|---|---|
| 设计文档工具数 25 与实现 26 不一致（领退对称多出 query_material_return） | P3 | **已修**：docs/M5拆解-生产Agent工具.md §0/§1/§4/§6/§7 校准为 26（HIGH 19/NORMAL 7）、常驻 95、全量 97 |

## 4. 跑测命令
```powershell
$env:JAVA_HOME='C:\Users\Sephiroth\.jdks\jdk21\jdk-21.0.11+10'
mvn -f server/pom.xml clean test          # 全反应堆单测（含 85 工具单测 + 注册计数断言）
# T07 无 @Tag(integration-db) 新增（纯封装，底层服务已有真库覆盖）；勿加 -DexcludedGroups 覆盖
```

## 5. 说明
T07 不新增集成测：工具仅委托既有 AppService，库存唯一入口/移动加权/GL 借贷平衡/约当法/核销等正确性已由 M5-T04~T06、M4 各 `*FlowIntegrationTest` 真库验收。工具层风险全在「契约层」（风险级别/权限/缺参/委托/异常/operator），单测足以覆盖且无需起容器。
