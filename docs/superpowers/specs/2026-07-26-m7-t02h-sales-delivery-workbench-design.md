# M7-T02h 销售出库工作台设计

## 范围

本批在销售模块保留“客户档案 / 销售订单”，新增“销售出库”二级页签。工作台只覆盖真实分页、详情、创建草稿、审核、过账和草稿作废能力；不增加编辑、删除、销售出库冲销、销售发票、库存、生产或财务工作台，也不提前进入 M7-T03。

后端已有 `reverse`，但它会按原 COGS 反向入库、回退订单累计发货量并红冲会计凭证，属于高风险恢复流程，本批不暴露。`cancel` 是既有草稿真实端点，本批必须纳入。所有写操作继续经 `SalesDeliveryController → SalesDeliveryAppService → SalesDeliveryService`，库存变化只经库存唯一写入口，领域状态机、审计与自动凭证契约不改变。

销售出库页签严格由 `permissions.includes('sales:delivery')` 控制，后端 `SalesDeliveryController` 的类级 `sales:delivery` 仍是最终授权边界。

## 权限缝隙与窄读投影

`WAREHOUSE` 持有 `sales:delivery`，但不持有 `sales:order`。新建出库单若直接调用 `SalesOrderController`，仓管无法完成实际发货职责；因此不能复用订单 Controller 的读接口，也不能授予额外订单权限。

在 `/api/sales/deliveries/**` 的既有类级权限边界内增加两个只读投影端点：

- `GET /api/sales/deliveries/order-options?page=&size=`：只返回 `APPROVED` 或 `EXECUTING` 且至少一行 `quantity > delivered_qty` 的销售订单；
- `GET /api/sales/deliveries/order-options/{docNo}`：再次读取真实订单，状态不再允许发货或已经全部发完时返回 404。

投影行只含建单需要的真实订单行号、商品、订购数量、单价、金额、已发量和剩余可发量，并过滤已发完行。端点不授予 `sales:order`，不暴露订单创建、审核、作废或其他写能力；最终创建仍由 `SalesDeliveryService` 校验订单状态、引用行、商品一致性和跨出库单累计剩余数量。

候选过滤不能在应用服务分页后做内存过滤。`SalesOrderQuery` 新增 `deliverableOnly`，同时保留既有四参数构造器；`JdbcSalesOrderRepository` 在 count 和 `LIMIT/OFFSET` 之前把状态限定为 `APPROVED/EXECUTING`，并使用 tenant-correlated `EXISTS` 检查未发完行。这样已发完订单不计入 total、不占页位，也不会把后续可发货订单藏到其他源分页中。Controller 列表直接映射仓储已筛选的当前页；详情保留二次门禁应对选择后的状态变化。

## 真实出库契约

- 列表：`GET /api/sales/deliveries?warehouseId=&salesOrderNo=&status=&page=&size=`。
- 详情：`GET /api/sales/deliveries/{docNo}`。
- 创建：`POST /api/sales/deliveries`。
- 审核：`POST /api/sales/deliveries/{docNo}/approve`，仅 `DRAFT → APPROVED`。
- 过账：`POST /api/sales/deliveries/{docNo}/post`。
- 作废：`POST /api/sales/deliveries/{docNo}/cancel`，仅草稿可用。

创建请求由 API 层显式窄化为：

```json
{
  "salesOrderNo": "SO-202607-0001",
  "warehouseId": 1,
  "remark": "第一批发货",
  "lines": [
    {
      "soLineNo": 1,
      "productId": 2,
      "quantity": "10.000000"
    }
  ]
}
```

销售订单必须从上述候选投影选择，明细引用真实 `soLineNo`；仓库从真实启用仓库中选择。前端只允许选择未发完行，并以字符串比较校验本次发货量不超过剩余可发量；后端领域服务仍是并发与最终一致性的守门人。

数量、订单金额、已发量、剩余量和 COGS 全程使用 BigDecimal 字符串。若后端或测试替身返回 JSON number，API 边界立即归一化为字符串；前端不调用 `Number`、`parseFloat` 或浮点运算处理业务数值。

## 过账影响与交互

销售出库过账会在同一外层事务中：

1. 经库存唯一入口真实扣减库存；
2. 按移动加权成本固化每行 COGS；
3. 回写销售订单累计发货量；
4. 生成销售出库自动凭证。

库存不足时整张出库单过账失败并全部回滚，不留下部分库存流水、部分 COGS、部分订单回写或孤立凭证。详情过账按钮旁和二次确认必须完整呈现上述影响与整批回滚语义。

列表、详情、销售订单候选、仓库选择和商品名称补载分别维护请求版本；同条件查询或候选刷新会递增刷新键，旧响应不能覆盖当前选择。仓库与商品成功补载后显示“名称（编码）”，失败时确定性显示“仓库/商品 #id（名称不可用）”。

创建、审核、过账和作废共用组件级同步 `useRef` in-flight 守卫，事件入口先置位并在 `finally` 释放。所有按钮声明 `type`，错误使用 `role="alert"`，成功使用 `role="status"`。
