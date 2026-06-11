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
| LLM 接入 | 自建抽象层，可切换 DeepSeek / 通义 / Claude / GPT | 规划中 |

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

### 后端

```bash
cd server
mvn -DskipTests install   # 首次或模块代码变更后，把兄弟模块装入本地 Maven 仓库
mvn spring-boot:run -pl sjherp-app
# 依赖：JDK 21、Maven 3.9+，以及可访问的 MySQL（见上）
```

启动后可用 `GET /api/health` 探活；会话 API 见 `server/sjherp-app/.../chat/ChatSessionController.java`
（POST /api/chat/sessions、GET /api/chat/sessions/{id}、POST /api/chat/sessions/{id}/messages）。

### 前端

```bash
cd web
npm install
npm run dev
```

## 下一步

接入 LLM 抽象层的第一个具体实现（替换 PlaceholderAgent）、领域工具注册、Qdrant 大记忆接入。
