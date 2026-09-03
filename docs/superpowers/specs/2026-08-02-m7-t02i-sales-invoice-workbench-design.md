# M7-T02i 销售发票工作台设计

## 范围

本批在销售模块保留“客户档案 / 销售订单 / 销售出库”，新增“销售发票”二级页签。工作台只覆盖真实分页、详情、创建草稿、审核、过账和草稿作废；不增加编辑、删除、销售发票冲销、应收核销、报表、流式回复或聊天体验改造。

后端已有 `reverse`，但它会回退出库行已开票量、冲回应收并红冲会计凭证，属于高风险恢复流程，本批不暴露。`cancel` 是既有真实草稿端点，可以安全纳入。所有写操作继续经 `SalesInvoiceController → SalesInvoiceAppService → SalesInvoiceService`，领域状态机、应收挂账、自动凭证和审计契约不改变。

销售发票页签严格由 `permissions.includes('sales:invoice')` 控制，后端 `SalesInvoiceController` 的类级 `sales:invoice` 是最终授权边界。前端不复制角色矩阵。

## 权限缝隙与窄读投影

`ACCOUNTANT` 持有 `sales:invoice`，但不持有 `sales:delivery`。新建发票若直接调用 `SalesDeliveryController`，会计无法完成开票；不能复用出库 Controller 的读接口，也不能授予额外出库权限。

在 `/api/sales/invoices/**` 的既有类级权限边界内增加两个只读投影端点：

- `GET /api/sales/invoices/delivery-options?page=&size=`：只返回 `COMPLETED` 且至少一行 `quantity > invoiced_qty` 的销售出库单；
- `GET /api/sales/invoices/delivery-options/{docNo}`：再次读取真实出库单，状态不再为 `COMPLETED`、已经冲销或全部开完时返回 404。

投影头只含出库单号、订单号、仓库、说明、状态和未开完行；行只含真实出库行号、商品、发货量、已开票量和剩余可开票量。COGS 与出库总成本不是建票所需字段，明确不暴露。端点不授予 `sales:delivery`，不暴露出库创建、审核、过账、作废或冲销能力；最终创建仍由 `SalesInvoiceService` 校验出库状态、引用行、商品一致性和跨发票累计剩余数量。

候选过滤不能在应用服务分页后做内存过滤。`SalesDeliveryQuery` 新增 `invoiceableOnly`，并保留既有五参数构造器；`JdbcSalesDeliveryRepository` 在 count 和 `LIMIT/OFFSET` 前强制 `COMPLETED`，使用 tenant-correlated `EXISTS` 检查未开完行。已开完、草稿和已冲销出库单不计入 total、不占页位。详情保留二次门禁应对选择后的状态变化。

## 真实发票契约

- 列表：`GET /api/sales/invoices?customerId=&salesDeliveryNo=&status=&page=&size=`；
- 详情：`GET /api/sales/invoices/{docNo}`；
- 创建：`POST /api/sales/invoices`；
- 审核：`POST /api/sales/invoices/{docNo}/approve`，仅 `DRAFT → APPROVED`；
- 过账：`POST /api/sales/invoices/{docNo}/post`；
- 作废：`POST /api/sales/invoices/{docNo}/cancel`，仅草稿可用。

创建请求由 API 层显式窄化为：

```json
{
  "salesDeliveryNo": "SD-202608-0001",
  "invoiceDate": "2026-08-02",
  "dueDate": "2026-09-01",
  "remark": "八月货款",
  "lines": [
    {
      "deliveryLineNo": 1,
      "productId": 2,
      "quantity": "10.000000",
      "unitPrice": "25.000000"
    }
  ]
}
```

客户 id 由服务端沿“出库单 → 销售订单”推导，前端不得提交。销售出库单必须从窄读候选选择，明细引用真实 `deliveryLineNo/productId`。前端只允许加入未开完行，并以十进制字符串比较校验本次开票量不超过剩余可开票量；服务端领域服务仍是并发与最终一致性的守门人。

数量、单价、金额、已开票量和剩余量全程使用 BigDecimal 字符串。JSON number 响应在 API 边界归一化为字符串；前端不使用 `parseFloat` 或浮点运算。数量/单价在前端正则与 `SalesInvoiceLine` 领域工厂双重守住 `DECIMAL(18,6)` 的正负、12 位整数和 6 位小数边界；前端以 BigInt 对每行字符串乘积按后端 HALF_UP 到 2 位，再精确累加校验行金额与发票头总金额，领域聚合对总金额再次守门，防止超出 `DECIMAL(18,2)`。

## 过账影响、竞态与回退

销售发票过账在同一外层事务中回写出库行累计已开票量、生成应收 OPEN、生成销售发票自动凭证（借 1122 应收账款 / 贷 6001 主营业务收入），并完成单据状态。任一步失败，发票、出库回写、应收与凭证整体回滚。详情操作区和二次确认必须完整呈现上述影响。

所有发票状态写先在既有外层事务中以 tenant-scoped `SELECT ... FOR UPDATE` 锁定发票头；过账/冲销随后经 `recordInvoiced/reverseInvoiced` 锁定出库单头，固定“发票头 → 出库头”顺序。冲销外层事务在任何自动凭证查询前先调用领域 `lockForReverse`：若与尚未提交的 post 竞争，会先等待发票锁，再以包含已提交应收与自动凭证的新快照继续红冲，避免先查不到凭证却随后看到 `COMPLETED` 的跨快照漏冲。这样同一发票的重复过账/冲销会在后到事务拿到发票锁后以最新状态拒绝，不会二次回写；不同发票竞争同一出库单时则由出库锁串行化，后到事务读取最新 `invoicedQty` 后再执行领域累计量守门。锁不创建新写入口，任一步失败仍由既有外层事务整单回滚。

创建、审核、过账和草稿作废共用组件级同步 in-flight 守卫，保存期间禁止切换列表、分页、查询或候选。列表、详情、出库候选、候选详情、客户名称、仓库名称和商品名称补载分别使用可执行 request gate；旧响应不能覆盖新选择，候选翻页/刷新会立即作废在途候选详情。名称补载失败时确定性显示“客户/仓库/商品 #id（名称不可用）”。所有按钮声明 `type`，错误使用 `role="alert"`，成功或加载提示使用 `role="status"`。
