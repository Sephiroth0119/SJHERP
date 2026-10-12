# 传统 UI 第四刀：凭证工作台

## 当前状态

从 `main`（#51 资金账户 / 应付 / 应收）派生。财务模块新增「凭证」页签；**不做冲销、试算/科目余额、账期关账、科目维护、草稿编辑删除**，也不改预发 `web-dist` 静态包。

## 验收范围

- 财务模块页签新增 **凭证**，仅 `finance:voucher` 可见；无该权限 fail-closed 隐藏。
- 列表：`GET /api/gl/vouchers?period=&status=&page=&size=`；账期 yyyyMM（月份选择或文本），状态 DRAFT/APPROVED/REVERSED/CANCELLED。
- 详情：`GET /api/gl/vouchers/{docNo}`，行内借/贷金额字符串。
- 新建草稿：`POST /api/gl/vouchers`，前端用字符串金额累加校验 Σ借==Σ贷 且 ≥2 行、恰好一方>0，未平衡禁用提交。
- 过账：`POST /api/gl/vouchers/{docNo}/post`，仅 DRAFT 且有写权限；页内确认（非 `window.confirm`）。400 借贷不平 / 409 关账期展示 `ApiError.message`。
- 科目下拉：`GET /api/gl/accounts?leafOnly=true`（登录即可），客户端再滤 `enabled && leaf`，展示 `code · name`。
- 金额一律字符串；写动作同步 in-flight 守卫；列表/详情/科目均有 request gate。
- 凭证字固定「记」不编辑；手工凭证不填 sourceDocNo/sourceDocType。
