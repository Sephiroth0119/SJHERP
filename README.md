# SJHERP — 下一代 Agent 原生 ERP

面向**小型企业**的进销存 + 生产 + 财务一体化 ERP：用户通过 **Agent 聊天界面**完成业务操作（同时保留传统业务入口），Agent 在需要决策时返回可点击的**选项卡片**，而不是让用户打字描述一切。

> 项目愿景、不可妥协原则与开发约定详见 [CLAUDE.md](./CLAUDE.md)。

## 技术栈

| 层 | 选型 | 状态 |
|---|---|---|
| 后端 | Java 21 + Spring Boot 3.x（Maven 多模块） | 骨架已搭建，会话 API 已实现 |
| Agent 框架 | 完全自研（会话状态持久化、工具注册、选项返回协议） | 核心抽象已实现（选项返回协议 v0.1）；会话持久化到 MySQL（ADR-001） |
| 前端 | React + TypeScript（Vite） | 聊天界面已对接后端会话 API（mock 可用 VITE_USE_MOCK=true 切回） |
| 业务数据库 | MySQL 8.x（InnoDB，强事务） | 开发环境已接入（Flyway 迁移） |
| 向量库（大记忆） | 候选 Qdrant（待定） | 开发环境已部署，待接入 |
| LLM 接入 | 自建抽象层，OpenAI 兼容多 provider 配置化（DeepSeek / 通义 / Kimi / GPT） | 多 provider + 角色（chat/summarizer/checker）配置化（M1-T07），DeepSeek 已接入 |

## 目录结构

```
SJHERP/
├── CLAUDE.md              # 项目愿景与开发约定（最高准则）
├── README.md
├── docs/                  # ADR、领域模型文档、协议定义、会计核算规则
│   ├── adr/               # 架构决策记录
│   ├── 选项返回协议.md     # Agent 前后端核心契约（版本化）
│   └── 领域模型概览.md
├── server/                # Java 后端（Maven 多模块）
│   ├── sjherp-agent/      # 自研 Agent 框架（领域无关）
│   ├── sjherp-domain/     # 领域模型与领域服务
│   ├── sjherp-app/        # 应用层：Tool 定义、API、编排入口
│   └── sjherp-infra/      # 持久化、LLM 抽象层、向量库客户端
└── web/                   # React + TS 前端
```

## 如何启动

### 前置：开发中间件（MySQL + Qdrant）

后端启动时连接 MySQL 并自动执行 Flyway 迁移，二选一：

1. **使用现有开发 VM**：能访问 `192.168.237.133`（MySQL 3306 / Qdrant 6333，`application.yml` 的默认值即指向它），无需额外配置；
2. **自行起环境**：`MYSQL_ROOT_PASSWORD=xxx docker compose -f deploy/docker-compose.dev.yml up -d`，
   然后用环境变量 `SJHERP_DB_URL` 指向自己的 MySQL（账号/密码默认 `sjherp_app` / `sjherp_dev_2026`，
   可用 `SJHERP_DB_USERNAME` / `SJHERP_DB_PASSWORD` 覆盖；生产环境必须覆盖）。

### LLM 配置（多 provider，M1-T07）

LLM 接入为**多 provider 配置化**（前缀 `sjherp.llm`），切换/新增模型只改配置不改代码。
所有 provider 走 OpenAI 兼容协议（`OpenAiCompatibleLlmClient` 一套实现），DeepSeek /
通义 compatible-mode / Kimi / GPT 均可接入。结构（见 `application.yml`）：

```yaml
sjherp:
  llm:
    default-provider: deepseek    # roles 未覆盖的角色回落到它
    providers:                    # provider 名 → 连接配置
      deepseek:
        base-url: https://api.deepseek.com
        model: deepseek-chat
        api-key: ${SJHERP_LLM_API_KEY:}   # 环境变量引用，绝不写明文
        temperature: 0.7          # 可省略，默认 0.7
        timeout-seconds: 60       # 可省略，默认 60
    roles:                        # Agent 角色 → provider 名
      chat: deepseek              # 对话主链路
      summarizer: deepseek        # 会话历史摘要（M1-T05）
      checker: deepseek           # 检查 Agent（M6 接入，建议配强模型）
```

`roles` / `default-provider` 指向未定义的 provider 名会在**启动期 fail-fast** 报错；
同一 provider 被多个角色引用时共用同一客户端实例。

聊天链路由 LLM 驱动（`sjherp.agent.mode=auto`，默认）：chat 角色的 provider 配置了
API Key 走 `LlmAgent`，否则回退规则占位 `PlaceholderAgent`（启动日志有 WARN 提示）。
`sjherp.agent.mode` 可显式指定 `llm` / `placeholder`。

API Key **绝不写进任何会被 git 跟踪的文件**，二选一：

1. **环境变量**（推荐，生产唯一方式）：

   ```powershell
   $env:SJHERP_LLM_API_KEY = "sk-xxx"
   mvn spring-boot:run -pl sjherp-app
   ```

2. **local profile**（本地开发）：创建 `server/sjherp-app/src/main/resources/application-local.yml`
   （已被 .gitignore 忽略），按 provider 嵌套写法（原平铺 `sjherp.llm.api-key` 已废弃）：

   ```yaml
   # 本地开发密钥，勿提交
   sjherp:
     llm:
       providers:
         deepseek:
           api-key: sk-xxx
   ```

   然后带 local profile 启动：

   ```bash
   mvn spring-boot:run -pl sjherp-app "-Dspring-boot.run.profiles=local"
   ```

### 登录与默认账号（M2-T05）

所有 `/api/**` 接口（除 `POST /api/auth/login` 与 `GET /api/health`）均需登录后携带
`Authorization: Bearer <token>` 访问，否则返回 401 `{"error":"未登录或登录已过期"}`。

- **初始管理员**：用户名 `admin`，密码 `Admin@2026`（V6 迁移种子数据）。
  **生产/正式环境部署后必须立即登录修改该密码**（管理员可在用户管理 API 重置）。
- **登录**：`POST /api/auth/login`，请求体 `{"username","password"}`，响应
  `{"token","displayName","roles"}`；token 为 JWT（HS256），有效期 12 小时。
- **JWT 密钥**：`application.yml` 中的默认值仅限本地开发，生产环境必须用环境变量
  `SJHERP_JWT_SECRET`（≥ 32 字节随机串）覆盖，否则任何人都能伪造登录态。
- **用户管理**（仅 ADMIN 角色）：`/api/identity/users`（列表/新建、`{id}/roles` 改角色、
  `{id}/enable|disable` 启停、`{id}/password` 重置密码）。用户不可删除，离职即停用。
- **当前用户**：`GET /api/auth/me`。前端登录页 + 右上角退出已对接，token 存 localStorage。

### 后端

```bash
cd server
mvn -DskipTests install   # 首次或模块代码变更后，把兄弟模块装入本地 Maven 仓库
mvn spring-boot:run -pl sjherp-app "-Dspring-boot.run.profiles=local"   # 不需要 LLM 时可省略 profile 参数
# 依赖：JDK 21、Maven 3.9+，以及可访问的 MySQL（见上）
```

启动后可用 `GET /api/health` 探活；会话 API 见 `server/sjherp-app/.../chat/ChatSessionController.java`
（POST /api/chat/sessions、GET /api/chat/sessions/{id}、POST /api/chat/sessions/{id}/messages）。

**API 调试文档（仅 local/dev profile）**：local profile 启动后访问 `http://localhost:8080/doc.html`；
用 `POST /api/auth/login`（用户名 `admin`，密码 `Admin@2026`）获取 token，点击右上角 `Authorize` 按钮填入 token，
之后所有接口请求将自动携带认证头。生产环境文档路径强制关闭（返回 401）。

### 前端

```bash
cd web
npm install
npm run dev
```

## 下一步

领域工具（Tool）注册并接入 LLM 工具调用、流程缺口记录通道落地、Qdrant 大记忆接入。
