# M7-T02d 采购订单工作台设计

## 范围

本批在采购模块保留 T02b 供应商档案，并增加“供应商档案 / 采购订单”紧凑二级切换。采购订单只覆盖现有后端已经公开的分页、详情、创建草稿、审核和关闭能力；不增加编辑、删除、作废、冲销、入库或发票入口，不改变全局导航和后端领域契约。

采购订单页签仅对持有 `purchase:order` 的登录用户显示。供应商档案仍沿用 `partner:create_supplier`、`partner:write` 控制维护动作；后端始终是最终授权边界。

## 真实契约与精度

- 列表：`GET /api/purchase/orders?supplierId=&status=&page=&size=`。
- 详情：`GET /api/purchase/orders/{docNo}`。
- 创建：`POST /api/purchase/orders`。
- 审核：`POST /api/purchase/orders/{docNo}/approve`，仅 `DRAFT → APPROVED`。
- 关闭：`POST /api/purchase/orders/{docNo}/close`，后端领域层完成 `APPROVED → EXECUTING → COMPLETED`。

创建请求由 API 层显式窄化为：

```json
{
  "supplierId": 1,
  "orderDate": "2026-07-26",
  "remark": "采购说明",
  "lines": [
    {
      "productId": 2,
      "quantity": "10.000000",
      "unitPrice": "12.340000"
    }
  ]
}
```

`supplierId`、`productId` 只作为整数标识处理。数量、单价、行金额、订单总额、已到货量和未到货量全程使用字符串，不调用 `Number`、`parseFloat` 或其他浮点换算。前端按领域与数据库边界校验：至少一行、同单商品不重复、数量大于 0、单价非负、整数最多 12 位且小数最多 6 位；最终校验仍由后端领域层负责。

## 引用选择与请求隔离

供应商与商品都使用现有真实分页搜索 API，以编码和名称呈现，不提供裸 ID 输入：

- 供应商：`GET /api/partner/suppliers?keyword=&status=&page=&size=`；创建时只列启用供应商。
- 商品：`GET /api/catalog/products?keyword=&status=ENABLED&page=&size=`。
- 订单列表或详情响应只含引用 id 时，使用对应详情 API 补齐可读名称；名称加载不阻塞订单列表。

订单列表、订单详情、供应商选择和商品选择各自维护请求版本。相同条件点击查询仍递增刷新键并重新请求；翻页和改筛选会使旧列表/详情响应失效，避免陈旧响应覆盖当前选择。

引用详情补载成功后显示“名称（编码）”；补载失败或尚不可用时确定性显示“供应商/商品 #id（名称不可用）”，不以永久“加载中”误导操作员。

## 操作员界面

界面复用 T02a–T02c 的紧凑列表、详情侧栏、普通表单和状态反馈，不引入组件库。列表行和引用选项均可用键盘选择；按钮明确声明 `type`，加载或提交期间禁用；错误使用 `role="alert"`，成功使用 `role="status"`。

状态 badge 使用中文：草稿、已审核、执行中、已关闭、已作废、已冲销。详情仅在草稿显示“审核”，仅在已审核显示“关闭订单”；没有后端端点的动作不展示。

创建、审核和关闭共用组件级同步 `useRef` in-flight 守卫：事件处理入口先检查并置位，业务请求在 `finally` 中释放。按钮 `disabled` 只负责反馈，快速双击下的防重复写不依赖 React state 生效时序。
