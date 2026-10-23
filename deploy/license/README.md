# 离线 license 测试密钥（M8-T08）

本目录的 `dev-ed25519-*.pem` **只用于 CI、文档样例和本地签发测试**，不是生产签发密钥。

- 生产验签公钥由供应商单独交付，写入 `SJHERP_LICENSE_PUBLIC_KEY_PATH` 或 `SJHERP_LICENSE_PUBLIC_KEY`
- 生产私钥不要放进 git，也不要打进镜像
- 客户 license 文件放到 `deploy/license/sjherp.license.json`（已被 gitignore），compose 只读挂载到 `/app/license/`

签发测试 license：

```bash
./deploy/license.sh --issue --subject "试点客户" --expires 2027-10-23 --out deploy/license/sjherp.license.json
```
