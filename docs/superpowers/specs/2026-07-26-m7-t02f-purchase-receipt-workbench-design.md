# M7-T02f 采购入库工作台设计

## 范围

本批在采购模块保留“供应商档案 / 采购订单”，新增“采购入库”二级页签。工作台只覆盖真实分页、详情、创建草稿、审核和过账能力；不增加编辑、删除、采购发票、销售出库/发票、库存总览、生产或财务工作台。

后端没有采购入库草稿作废公开端点，因此界面不虚构作废动作。`reverse` 是已完成单据的高风险恢复流程，本批不暴露。领域状态机、采购入库应用服务、审计、迁移和既有订单写权限均不改变。

采购入库页签严格由 `permissions.includes('purchase:receipt')` 控制，后端 `PurchaseReceiptController` 的类级 `purchase:receipt` 仍是最终授权边界。

## 权限缝隙与窄读投影

`docs/权限矩阵.md` 与 `docs/业务-采购.md` 明确 WAREHOUSE 持有 `purchase:receipt` 并负责实际收货，但不持有 `purchase:order`。如果新建表单调用采购订单 Controller，仓管只能维护已有入库单，不能完成核心收货职责。

为此在 `/api/purchase/receipts/**` 的既有类级权限边界内增加两个只读投影端点：

- `GET /api/purchase/receipts/order-options?page=&size=`：只返回 `APPROVED` 且至少有一行 `quantity > received_qty` 的采购订单；
- `GET /api/purchase/receipts/order-options/{docNo}`：再次读取真实订单，状态不再为 `APPROVED`、已经关闭或全部收完时返回 404。

投影行只含建单所需的真实订单行号、商品、订购数量、单价、已收量和未收量，并过滤已收完行。端点不授予 `purchase:order`，不暴露订单创建、审核、关闭或其他写能力；最终创建仍由 `PurchaseReceiptService` 校验订单状态、引用行和累计未收数量。

候选过滤不能在应用服务分页后做内存过滤。`PurchaseOrderQuery` 新增 `receivableOnly`，同时保留既有四参数构造器；`JdbcPurchaseOrderRepository` 在 count 和 `LIMIT/OFFSET` 之前使用关联 `EXISTS` 检查未收行。这样已收完订单不计入 total、不占页位，也不会把后续可收订单藏到其他源分页中。Controller 列表直接映射仓储已筛选的当前页；详情仍保留二次门禁应对选择后的状态变化。

## 真实入库契约

- 列表：`GET /api/purchase/receipts?warehouseId=&purchaseOrderNo=&status=&page=&size=`。
- 详情：`GET /api/purchase/receipts/{docNo}`。
- 创建：`POST /api/purchase/receipts`。
- 审核：`POST /api/purchase/receipts/{docNo}/approve`，仅 `DRAFT → APPROVED`。
- 过账：`POST /api/purchase/receipts/{docNo}/post`；增加库存、回写订单到货量并生成会计凭证。

创建请求由 API 层显式窄化为：

```json
{
  "purchaseOrderNo": "PO-202607-0001",
  "warehouseId": 1,
  "receiptDate": "2026-07-26",
  "remark": "第一批到货",
  "lines": [
    {
      "poLineNo": 1,
      "quantity": "10.000000",
      "unitCost": "12.340000"
    }
  ]
}
```

采购订单必须从上述候选投影选择，明细引用真实 `poLineNo`；仓库从真实启用仓库中选择，不要求业务用户填写裸单号或 ID。前端只允许选择未收行，并以字符串比较校验本次收货量不超过未收量；后端领域服务仍是并发与最终一致性的守门人。

数量、单价、金额、已收量和未收量全程使用 BigDecimal 字符串。若后端或测试替身返回 JSON number，API 边界立即归一化为字符串；前端不调用 `Number`、`parseFloat` 或浮点运算处理业务数值。

## 交互与竞态

列表、详情、采购订单候选和仓库选择各自维护请求版本；同条件查询或候选刷新会递增刷新键，旧响应不能覆盖新选择。引用名称补载成功显示“名称（编码）”，失败时确定性显示“仓库/商品 #id（名称不可用）”。

创建、审核、过账共用组件级同步 `useRef` in-flight 守卫，事件入口先置位并在 `finally` 释放。过账按钮旁明确说明其库存、订单到货量和会计凭证影响；执行前必须经过包含同样影响说明的二次确认。所有按钮声明 `type`，错误使用 `role="alert"`，成功使用 `role="status"`。
