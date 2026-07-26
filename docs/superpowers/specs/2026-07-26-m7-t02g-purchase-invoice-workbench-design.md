# M7-T02g 采购发票工作台设计

## 范围

本批在采购模块保留“供应商档案 / 采购订单 / 采购入库”，新增“采购发票”二级页签。工作台只覆盖真实分页、详情、创建草稿、审核和过账能力；不增加编辑、删除、草稿作废、采购发票冲销、销售出库/发票、库存、生产或财务工作台。

后端已有 `reverse`，但它会同时回退入库行已开票量、冲回应付并红冲会计凭证，属于高风险恢复流程，本批不暴露。领域状态机、采购发票应用服务、应付生成、自动凭证和审计契约均不改变。

采购发票页签严格由 `permissions.includes('purchase:invoice')` 控制，后端 `PurchaseInvoiceController` 的类级 `purchase:invoice` 仍是最终授权边界。

## 权限缝隙与窄读投影

`ACCOUNTANT` 持有 `purchase:invoice`，但不持有 `purchase:receipt`。新建发票若直接调用 `PurchaseReceiptController`，会计无法完成发票登记；因此不能复用入库 Controller 的读接口，也不能授予额外入库权限。

在 `/api/purchase/invoices/**` 的既有类级权限边界内增加两个只读投影端点：

- `GET /api/purchase/invoices/receipt-options?page=&size=`：只返回 `COMPLETED` 且至少一行 `quantity > invoiced_qty` 的采购入库单；
- `GET /api/purchase/invoices/receipt-options/{docNo}`：再次读取真实入库单，状态不再为 `COMPLETED`、已经冲销或全部开完时返回 404。

投影行只含建单所需的真实入库行号、商品、收货数量、收货单价、收货金额、已开票量和剩余可开票量，并过滤已开完行。端点不授予 `purchase:receipt`，不暴露入库创建、审核、过账、冲销或其他写能力；最终创建仍由 `PurchaseInvoiceService` 校验入库状态、引用行和跨发票累计剩余数量。

候选过滤不能在应用服务分页后做内存过滤。`PurchaseReceiptQuery` 新增 `invoiceableOnly`，同时保留既有五参数构造器；`JdbcPurchaseReceiptRepository` 在 count 和 `LIMIT/OFFSET` 之前使用关联 `EXISTS` 检查未开完行，并把状态限定为 `COMPLETED`。这样已开完入库单不计入 total、不占页位，也不会把后续可开票入库单藏到其他源分页中。Controller 列表直接映射仓储已筛选的当前页；详情保留二次门禁应对选择后的状态变化。

## 真实发票契约

- 列表：`GET /api/purchase/invoices?supplierId=&purchaseReceiptNo=&status=&page=&size=`。
- 详情：`GET /api/purchase/invoices/{docNo}`。
- 创建：`POST /api/purchase/invoices`。
- 审核：`POST /api/purchase/invoices/{docNo}/approve`，仅 `DRAFT → APPROVED`。
- 过账：`POST /api/purchase/invoices/{docNo}/post`；回写入库行累计开票量、生成应付账款并自动生成会计凭证。

创建请求由 API 层显式窄化为：

```json
{
  "purchaseReceiptNo": "PR-202607-0001",
  "invoiceDate": "2026-07-26",
  "supplierInvoiceNo": "SUP-INV-001",
  "remark": "七月货款",
  "lines": [
    {
      "receiptLineNo": 1,
      "quantity": "10.000000",
      "amount": "123.45"
    }
  ]
}
```

采购入库单必须从上述候选投影选择，明细引用真实 `receiptLineNo`。前端只允许选择未开完行，并以字符串比较校验本次开票量不超过剩余可开票量；后端领域服务仍是并发与最终一致性的守门人。数量、金额、已开票量和剩余量全程使用 BigDecimal 字符串。若后端或测试替身返回 JSON number，API 边界立即归一化为字符串；前端不调用 `Number`、`parseFloat` 或浮点运算处理业务数值。

## 交互与竞态

列表、详情、入库单候选、供应商筛选及名称补载分别维护请求版本；同条件查询或候选刷新会递增刷新键，旧响应不能覆盖当前选择。供应商、仓库和商品成功补载后显示“名称（编码）”，失败时确定性显示“供应商/仓库/商品 #id（名称不可用）”。

创建、审核、过账共用组件级同步 `useRef` in-flight 守卫，事件入口先置位并在 `finally` 释放。过账按钮旁明确说明“回写已开票量、生成应付与会计凭证”；执行前必须经过包含同样影响说明的二次确认。所有按钮声明 `type`，错误使用 `role="alert"`，成功使用 `role="status"`。
