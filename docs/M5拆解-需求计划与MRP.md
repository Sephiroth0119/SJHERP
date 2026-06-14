# M5-T02 SOP/DP 需求计划 + MRP 简化展开 拆解与实现方案

> 路线图 §7 M5-T02（L 级）。本文是 T02 单一设计真源。
> 范围：需求计划录入（销售订单牵引 + 手工预测）→ BOM 展开 → 生产建议 + 采购建议（MRP 简化版，按可用库存逐层净算，不做 APS/lot-sizing/提前期）。
> 验收：教科书 MRP 案例逐层净算正确（A=90/B=160/C=430）。

## 0. 范围红线
- **做**：手工预测录入（DemandPlan）+ 销售订单需求实时聚合 + MRP 逐层净算 + 生产/采购建议输出（持久化运行结果）。
- **不做**：建议→工单（T03）/采购单执行转换、提前期/产能/APS、lot-sizing、取整到最小包装、Agent 工具（留 T07）。

## 1. 关键设计裁定
1. **DP 建模**：手工预测落 `demand_plan` 表（来源 FORECAST）；销售订单需求（来源 SALES_ORDER）MRP 运行时实时聚合 `SalesOrderLine.remainingQty()`，**不复制**（避免双写漂移，同"不冗余存储派生值"原则）。
2. **MRP 逐层 netting（核心）**：**不复用** explode 的毛需求树（explode 把毛需求当净需求向下传）。MRP 须"父件先减自己库存得净需求(=生产建议量)，再用**净需求**×行用量×(1+损耗)作子件毛需求"。按 low-level-code(LLC) 升序逐层净算，同层多来源毛需求先合并再一次净算（防重复减库存）。
3. **生产/采购分流**：有净需求物料——有 active BOM→生产建议（继续向下展开）；无 active BOM→采购建议（叶子，停止）。Product 无 make/buy 字段故以"有无 active BOM"为准（登记：未来加字段则字段优先、BOM 兜底）。
4. **MRP 运行持久化**（regenerative 全重算）：落 `mrp_run`+`mrp_suggestion`，供查询/T03 转工单/采购转换引用；重跑产生新运行号、历史保留。
5. **单位归一**（解决 T01 遗留）：MRP 全程在子件**基本单位**计算。BOM 行用量按行 unitId 经 `Product.unitConversions.toBaseQuantity` 换算到基本单位；库存 balanceOf.quantity() 已是基本单位；换算缺失抛 IllegalArgumentException（不静默）。乘法不舍入，最终建议量 6 位 HALF_UP。
6. **精度**：数量一律 BigDecimal/DECIMAL(18,6)；净需求负数截 0；本批不取整（登记增强）。
7. **在途采购/已排产产出不抵扣**（小企业简化，登记）；可用量=结存（无预留概念）。
8. **MRP 运行须指定 warehouseId**（库存按仓核算，无全仓汇总入口，登记多仓增强）。

## 2. 领域模型（com.sjherp.domain.production，续建）
- `DemandSourceType{FORECAST, SALES_ORDER}`、`SuggestionType{PRODUCTION, PURCHASE}`。
- `DemandPlan`（聚合根 AuditTarget：docNo DP-、planDate、remark、ArchiveStatus、行）+ `DemandPlanLine`（productId/quantity>0/unitId/dueDate?）+ Command/Query/Repository/Service(@Audited create/update)/NotFoundException。
- `MrpService`（纯领域，核心 `run(MrpRunRequest, operator)→MrpRun`，逐层 netting + 环检测路径防护）。依赖 BillOfMaterialsRepository、DemandPlanRepository、ProductRepository、`MrpDemandSource`（端口：openSalesOrderDemand()→Map<productId,基本单位剩余需求>）、`MrpInventorySource`（端口：onHand(warehouseId,productId)→基本单位结存）。
- `MrpRun`（聚合根：docNo MRP-、runAt、warehouseId、includeForecast/SalesOrder、建议行）+ `MrpSuggestion`（type/productId/level/grossRequirement/onHand/netRequirement/baseUnitId）+ MrpRunRepository + NotFoundException。

## 3. 迁移 V26__demand_plan_mrp.sql（4 表，照 V24/V25）
tenant_id DEFAULT 0 入唯一键最左、DATETIME(6) UTC、DECIMAL 禁 float、不可物删。
- `demand_plan`(id/tenant_id/doc_no/plan_date/status/remark/审计4)，uk(tenant_id,doc_no)。
- `demand_plan_line`(.../demand_plan_id/line_no/product_id/quantity DECIMAL(18,6)/unit_id/due_date)，uk(tenant_id,demand_plan_id,line_no)+idx head/product。
- `mrp_run`(.../doc_no/run_at/warehouse_id/include_forecast/include_sales_order/remark/审计4)，uk(tenant_id,doc_no)+idx warehouse。
- `mrp_suggestion`(.../mrp_run_id/line_no/suggestion_type/product_id/level/gross_requirement/on_hand/net_requirement DECIMAL(18,6)≥0/base_unit_id)，uk(tenant_id,mrp_run_id,line_no)+idx run/product/type。

## 4. MRP 逐层 netting 算法
```
run(warehouseId, includeForecast, includeSalesOrder):
  grossDemand = {}                                  # Map<productId, 基本单位毛需求>
  if includeSalesOrder: merge(demandSource.openSalesOrderDemand())
  if includeForecast:   for FORECAST 行: merge(toBase(productId, qty, unitId))
  llc = computeLowLevelCodes(grossDemand.keys, BOM 递归, 环路径集)   # 物料在所有 BOM 树最深层级
  netByProduct = grossDemand 副本
  for level in 升序(llc 层级):
    for productId in 该层有毛需求物料:
      gross = netByProduct[productId]; if gross<=0 continue
      onHand = inventorySource.onHand(warehouseId, productId)
      net = max(gross - onHand, 0); if net==0 continue
      if findActiveByProductId(productId) present:
        suggestions.add(PRODUCTION, productId, level, gross, onHand, net)
        for line in bom.lines:                      # 用 NET 展开（非 gross！与 explode 本质区别）
          childBase = toBase(line.childProductId, net*line.quantity*(1+line.scrapRate), line.unitId)
          netByProduct[line.childProductId] += childBase    # 环防护复用 T01 visitedPath
      else:
        suggestions.add(PURCHASE, productId, level, gross, onHand, net)   # 叶子停止
  return MrpRun(...)
```
**教科书验收**：A→2×B、B→3×C；独立需求 A=100；库存 A=10/B=20/C=50 → 生产 A 90、生产 B 160（90×2−20）、采购 C 430（160×3−50）。带损耗 B→3×C scrap0.1：C 毛 528、采购 478。共用子件多父需求累加后一次净算（LLC 同层合并）。

## 5. REST + 权限点
新增权限点 `production:plan`、`production:mrp`（ADMIN allOf 自动 + BOSS）。
- DemandPlanController `/api/production/demand-plans`：POST/PUT 护 production:plan；GET 查询登录即可。
- MrpController `/api/production/mrp`：POST /runs 触发（production:mrp）、GET /runs/{docNo}、/runs/{docNo}/suggestions?type=、/runs 历史（**受控动作查询同权 production:mrp**，照采购订单/盘点先例）。
- 异常 DemandPlanNotFound/MrpRunNotFound→404、IAE/BomCycle→400，经 ProductionExceptionHandler。
- app 层 TransactionalDemandPlanService/TransactionalMrpService（@Transactional 薄委托，run 多表写须单一外层事务）+ MrpInventorySourceAdapter（委托 TransactionalInventoryService.balanceOf）。

## 6. Agent 工具留 T07
本批只做 REST + 领域；run_mrp/query_mrp_suggestions/create_demand_plan 留 M5-T07 统一接入，复用本批权限点。

## 7. 测试
- MrpServiceTest（stub 端口）：单层净算（正/0）、分流（有 BOM→生产展开/无 BOM→采购叶子）、**多层教科书 A=90/B=160/C=430**、带损耗、单位换算（箱→瓶基本单位、换算缺失抛异常）、共用子件多父汇总、环防护抛 BomCycleException、空需求、onHand==gross→net0、来源开关。
- DemandPlanServiceTest：商品启用/数量>0/行号唯一/unitId 合法/整体替换。
- infra @Tag(integration-db)：demand_plan/mrp_run 头+行往返、按 docNo 装配、open SO 需求聚合 SQL（部分发货 remainingQty 正确）。
- app：MrpController/DemandPlanController + ApiPermission（401/403、运行→查询往返）。

## 8. 风险与遗留登记
1. 在途采购/已排产产出不抵扣（净需求偏高）。2. 可用量=结存无预留。3. MRP 须选仓（无全仓汇总）。4. 不取整到最小包装。5. LLC 递归性能（小企业可控）。6. mrp_run 重跑历史保留无 SUPERSEDED 标记，T03 引用须校验最新。7. 手工预测与销售订单同商品需求**相加**（标准 MRP 默认；"取大值消费预测"为可配置增强，登记）。

## 9. 评审修复（opus 单镜头对抗校验，已落地）
- **0 P0**：MRP 逐层 netting（net 而非 gross 向下展开）与菱形/共用件 LLC（最深层一次净算）经手工推演正确，教科书 A=90/B=160/C=430 由算法真实产出。
- **P1 已修**：MRP 查询端点（GET runs/{docNo}）原"登录即可"与设计 §5「受控查询同权 production:mrp」、Permission.PRODUCTION_MRP javadoc、采购订单先例三处冲突 → MrpController 改**类级 @PreAuthorize('production:mrp')** 覆盖全部端点，同步 MrpApiPermissionTest 查询越权 403 用例。
- **P2 已修**：①补**菱形 BOM**（A→B、A→C、B→D、C→D）LLC 正确性测试（D 最深层一次净算、仅一条建议行、带库存一次扣减）——防回归核心；②补单位换算缺失抛 IllegalArgumentException 测试。
- **P3 已修**：V26 `suggestion_type` 注释 PRODUCE→PRODUCTION；DemandPlanService 数量精度校验 scale()→stripTrailingZeros().scale() 对齐 BomLine/UnitConversion 全库口径。
- domain 模块新增 assertj-core（test scope，运行时仍零依赖）以支持 MRP 测试的流式断言。
