# M8-T08 离线许可骨架

私有部署（docker-compose）的可装机校验，不是 SaaS 计费中心。商业模式（买断/订阅/免费+服务）未定，本刀不锁死。

## 决定

- 文件：JSON + Ed25519（Java 21 内置，与 openssl pkeyutl 互认）。HMAC 需要把验签密钥交给客户，客户可自签，故不采用。
- 字段：`v/subject/issuedAt/expiresAt?/seats?/modules?/graceDays?/kid?/sig`
- 签名对象：固定顺序 canonical 文本，不依赖 JSON 键序
- 未配置：不挡业务（开发/试用宽松）
- 配置后：软提示 + 到期宽限；硬阻断仅 `SJHERP_LICENSE_ENFORCE=true` 且过期/坏签名/无法解析
- 不落库、不改 Flyway、不改领域状态机、不接支付 SDK、不改 `web-dist/`
- REST：`GET /api/license/status`，权限 `license:view`（ADMIN/BOSS）
- 前端：帮助页只读展示；无支付 UI

## 非目标

Stripe / 微信支付 / 订阅后台 / 按模块计费引擎 / 锁死买断或 SaaS。
