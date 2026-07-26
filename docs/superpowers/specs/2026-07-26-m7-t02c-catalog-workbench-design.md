# M7-T02c 商品 / 类目 / 单位工作台设计

## 范围

库存模块保留 T02b 的仓库档案入口，并增加仓库档案 / 商品档案二级切换。商品档案提供真实 catalog API 的列表、关键词与状态筛选、分页、详情、新建、编辑、启用与停用。

类目与单位仅作为商品表单的真实引用选择，并支持现有 POST/PUT 维护能力。后端虽存在 DELETE Controller，但其调用领域删除服务，属于物理删除入口；前端明确不展示删除按钮、不发送 DELETE 请求，也不补造启停动作。

## 契约与安全边界

- 商品查询：`GET /api/catalog/products?keyword=&status=&page=&size=`。
- 商品写入：`POST /api/catalog/products`、`PUT /api/catalog/products/{id}`；状态使用既有 `/enable`、`/disable`。
- 类目：`GET`、`POST`、`PUT` `/api/catalog/categories`。
- 单位：`GET`、`POST`、`PUT` `/api/catalog/units`。
- 新建商品权限为 `catalog:create_product`；编辑与启停、类目/单位维护为 `catalog:write`。查询沿用登录态。
- 响应到表单、表单到请求体均使用显式白名单；不回传 id、status、审计字段。
- 单位换算率按字符串承载，不做 JavaScript float 运算；前端与领域层一致拒绝零值、基本单位和重复换算单位。
- 列表请求使用版本号保护竞态；筛选条件相同再次查询仍递增请求版本并重新加载。

## 视觉结构

复用 T02b 的紧凑列表—详情—表单壳，商品字段使用强类型组件；不引入组件库或未来大框架。
