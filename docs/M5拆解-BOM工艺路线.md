# M5-T01 BOM + 工艺路线极简版 拆解与实现方案

> 路线图 §7 M5-T01（M 级，**M5 生产模块地基**）。本文是 T01 单一设计真源。
> 范围：BOM（多层、版本、损耗率）+ 工艺路线极简版（工序列表 + 工时）主数据维护。
> 验收：为某产品维护带版本的多层 BOM（父→子件，用量+损耗率）与极简工艺路线（有序工序+单位工时），供 T02 MRP 展开与 T06 成本归集消费。

## 0. 范围红线（本批只做 / 明确不做）
- **做**：BOM + 工艺路线两个主数据聚合的 CRUD + 版本管理 + 启停 + 保存时环校验 + 只读递归展开查询（explode）+ REST + 权限点。
- **不做**：MRP 净算/采购建议（T02）、工单（T03）、领料齐套（T04）、报工完工（T05）、成本归集与费率应用（T06）、Agent 工具（留 T07 统一做，见 §6）。

## 1. 关键设计裁定

1. **模型结构**：两个独立聚合 `BillOfMaterials`（header：父产品+版本+状态+备注；line：子产品+用量+损耗率+单位）、`Routing`（header：产品+版本+状态；operation：序号+工序名+单位工时+预留工作中心/费率）。

2. **多层 BOM = 单层存储 + 递归展开**。每个父件只存直接子件（single-level）；多层由「子件本身也是某 BOM 的父件」自然成树；展开为只读递归查询。理由：避免冗余、单一真相源（CLAUDE.md 原则1）、改子 BOM 不需重算上游。

3. **循环依赖防护（数据模型红线，双保险）**：
   - 保存时（create/update）：拒绝直接自引用（父=子）；对每个子件递归向上确认其 active BOM 树不含当前父产品（建 B→A 时若已有 A→B 则拒）。
   - 展开时（explode）：携带访问路径集合，重复入栈抛 `BomCycleException` 兜底（防御历史脏数据/跨版本成环）。

4. **损耗率语义**：`scrapRate` 存**小数**（0.05=5%），范围 `[0,1)`（≥1 拒绝，否则净需求发散）。口径采用制造业主流**加成法**：`毛需求 = 净需求 ×(1+scrapRate)`（SAP/用友/金蝶 BOM 默认；yield 法 /(1−r) 注释说明但不采用）。计算在 `BomLine.grossQuantity(netParentQty)`，乘法不舍入（舍入交调用方，同 UnitConversion 约定）。

5. **状态 = ArchiveStatus + 同产品至多一启用版本**：主数据用 `ArchiveStatus{ENABLED,DISABLED}`，不引入 BusinessDocument 状态机。同 productId 可多版本，**至多一条 ENABLED**；enable 时同事务先停同产品其他启用版本再启用目标。DB 用生成列 `active_flag`（ENABLED 时=product_id 否则 NULL）+ 唯一索引兜底。

6. **后续接口预留**：T02 读 `findActiveByProductId` + `explode(productId, qty)`；T06 读 `RoutingRepository.findActiveByProductId`（工序+单位工时）+ BOM 行损耗率；Routing operation 预留可空 `work_center`/`cost_rate` 列（本批不参与逻辑）。

7. **无业务编号**：BOM/Routing 以 (product_id, version) 自然标识，不引入 BOM-/RT- 单号（MRP 按 product 读取非按号；减少表面积）。如后续强需再加 doc_no（向后兼容加列）。

## 2. 领域模型（com.sjherp.domain.production，全新包）

- `BomLine`（record 值对象）：`childProductId, quantity(DECIMAL 18,6 >0), scrapRate(DECIMAL 8,6 [0,1)), unitId`；`grossQuantity(netParentQty)` 加成法不舍入。
- `BillOfMaterials`（聚合根 implements AuditTarget）：构造校验（行非空、子件不重复、不自引用）；`restore`（不校验）、`update`、`enable`/`disable`（重复操作拒）、`assignId`。
- `BillOfMaterialsService`（唯一写入口，@Audited）：create（存在性/启用校验→版本唯一→环检测→落库）、update、enable（版本唯一切换）、disable、get（404）、search、**explode(productId, qty)** 只读递归（路径检测环）。环检测经 `Repository.findChildProductIds(parentProductId)`（仅 active）。
- `Routing`/`RoutingOperation`/`RoutingService` 同构：工序序号单内唯一有序、standardHours>0、版本唯一切换、启停、findActiveByProductId。
- 异常：`BillOfMaterialsNotFoundException`、`RoutingNotFoundException`、`BomCycleException`。
- 展开结果：`BomExplosion`/`BomExplosionNode`（record）。

## 3. 迁移 V25__production_bom_routing.sql（4 表，照 V22/V16 风格）
- `bom`：id/tenant_id/product_id/version/status/remark/审计四列；`uk_bom_product_version(tenant_id,product_id,version)`；生成列 active_flag + `uk_bom_active(tenant_id,active_flag)`；`idx_bom_product`。
- `bom_line`：id/tenant_id/bom_id/line_no/child_product_id/quantity DECIMAL(18,6)/scrap_rate DECIMAL(8,6) DEFAULT 0/unit_id；`uk_bom_line(tenant_id,bom_id,line_no)`、`uk_bom_line_child(tenant_id,bom_id,child_product_id)`；`idx_bom_line_head`、`idx_bom_line_child`（环检测反查）。
- `routing`：同 bom 头（product_id/version/status/remark + active_flag 兜底）。
- `routing_operation`：id/tenant_id/routing_id/sequence_no/operation_name VARCHAR(200)/standard_hours DECIMAL(18,6)/work_center VARCHAR(100) NULL/cost_rate DECIMAL(18,6) NULL；`uk_routing_op(tenant_id,routing_id,sequence_no)`。
- 数量/工时/损耗一律 DECIMAL，禁止 float/double。

## 4. REST + 权限点
- BOM `/api/production/boms`：POST/PUT/{id}/enable/{id}/disable（护 `production:bom`）、GET search/{id}/explode（登录即可）。
- Routing `/api/production/routings`：CRUD + enable/disable（护 `production:routing`）、查询登录即可。
- 新增权限点 `production:bom`、`production:routing`（写护，查询登录即可——基础数据通则，对齐 catalog/warehouse）。`RolePermissions`：ADMIN 自动全含、BOSS 加两点。**本批不新增 PRODUCTION 角色**（避免牵动 identity，留 M5 后续评估）。
- 一个 `ProductionExceptionHandler`（@RestControllerAdvice basePackageClasses）覆盖两 Controller（404→404、校验/IAE→400 `{"error":...}`）。
- 装配 `ProductionInfraConfig`（两仓储 + 两服务 @Bean，复用 ProductRepository）。

## 5. 测试
- domain：`BomLineTest`（损耗计算/边界）、`BillOfMaterialsServiceTest`（创建/版本唯一/子件校验/自引用拒/**环检测多层**/同 BOM 子件重复拒/版本切换/启停/@Audited/404/**explode 单层·多层·含损耗·命中环·边界**）、`RoutingServiceTest`。
- app：`BomControllerTest`/`RoutingControllerTest`（201/400/403/404/分页/explode 结构/enable·disable）。
- 集成 `@Tag("integration-db")`：header+line 整体读写往返、findActiveByProductId 唯一、DB active_flag 唯一冲突兜底、explode 跨多 BOM 真库递归。

## 6. Agent 工具留 M5-T07
本批不做 Agent 工具：T07「生产 Agent Tools」统一设计，BOM/工艺工具并入；本批 Service 已是唯一写入口，T07 直接复用。

## 7. 风险与遗留登记
1. 生成列 active_flag 唯一索引兜底需验 MySQL 8 + Flyway 兼容；不可行则退化为 Service 层保证 + 启动一致性校验（登记债）。
2. 环检测保存时递归查询，小企业层级浅（≤5）可接受；深层级未来需物化路径优化。环检测仅 active 版本，跨版本成环由 explode 路径检测兜底。
3. 多租户债 ADR-002：沿用 tenant_id DEFAULT 0、WHERE 按 id，不新增债。
4. 未引入 PRODUCTION 角色（仅 ADMIN/BOSS 维护），留后续评估。
5. 损耗率口径锁定加成法；若 T06/客户惯例需 yield 法，改 grossQuantity + 注释（成本低，已登记）。
6. explode 不做单位归一（BOM 行 unitId 原样返回），单位换算留 T02 决定。

## 8. 评审修复（opus 单镜头对抗校验，已落地）
1. **P0 版本顺位**：`create` 须**先停同产品旧 ENABLED 版本再插入新 ENABLED**（原实现先插后停→真库撞 `uk_*_active` 唯一索引，被无约束的内存 Fake 掩盖）。BOM/Routing 两处 create 已改先停后插（`disableOtherEnabledVersions(productId, 0L, op)` 在 save 之前）；Fake 仓储 save 补 active 唯一约束模拟作单元级回归守门。`enable` 原本就是先停后启，正确。
2. **P1 事务边界**：生产领域服务 create/enable 多次仓储写须单一外层事务原子完成。领域层不可加 Spring 注解→照 `TransactionalInventoryService` 范式新增 app 层 `TransactionalBomService`/`TransactionalRoutingService`（方法级 `@Transactional` 薄委托，读 readOnly），控制器改注入包装类。
3. **P2 预留字段一致性**：`RoutingOperationRequest.costRate` 去除 `@NotNull`（与 workCenter 一致，T06 预留可空）；响应映射对 costRate=null 做空值守护。
4. 建单期另修 3 处：explode 漏乘行用量（应为 父需求×行用量×(1+损耗)）、RolePermissionsTest BOSS 期望集补两新权限点、DTO 行/工序列表补 `@Valid` 级联（否则嵌套字段缺失 NPE 而非 400）。
