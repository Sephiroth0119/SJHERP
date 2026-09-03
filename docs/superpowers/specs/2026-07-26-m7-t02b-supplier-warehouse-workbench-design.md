# M7-T02b 供应商与仓库传统工作台设计

## 范围

本批交付采购入口的供应商工作台和库存入口的仓库工作台，保留 T02a 客户工作台，不涉及商品、单据、库存业务、生产、财务、SSE、聊天或组件库。

## 真实契约

- 供应商：`/api/partner/suppliers`，查询登录即可；创建需要 `partner:create_supplier`，编辑和启停需要 `partner:write`。
- 仓库：`/api/warehouse/warehouses`，查询登录即可；创建需要 `warehouse:create_warehouse`，编辑和启停需要 `warehouse:write`。
- 列表使用 `keyword/status/page/size`；启停使用 `POST .../{id}/enable|disable`。
- 请求体由 API 层白名单映射，只发送真实 DTO 字段，不发送 id、status 或审计字段。仓库 `locationEnabled` 始终为布尔值。

## 交互与权限

工作台采用紧凑的列表 + 详情面板。筛选区使用 draft/applied 两组状态，查询按钮或 Enter 才发请求；列表行支持 Enter/Space 键盘选择。加载、空结果、错误、成功、保存中和禁用状态均有明确反馈。前端缺少精确权限时隐藏写操作，后端仍是最终授权边界。

供应商表单字段为 code/name/contactPerson/contactPhone/address/taxNo/settlementMethod；仓库表单字段为 code/name/address/manager/locationEnabled。新建可留空 code 自动编号，编辑必须填写 code。
