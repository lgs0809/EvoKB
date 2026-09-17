# EvoKB

面向企业资料管理、可信查询与知识生成的自托管知识平台。

项目将原始文件、版本、元数据和证据作为事实层，提供资料入库、混合检索、带原文引用的知识查询、企业百科、关系图谱、知识生成、审计和 MCP 接口。所有回答和派生知识都可以回到原始资料与对应版本。

## 主要能力

- **资料管理**：多文件、文件夹和 ZIP 上传，版本管理，业务元数据，批量入库与失败重试。
- **可信查询**：原始知识、摘要、百科和图谱联合检索，融合排序、重排与证据解析。
- **知识生成**：摘要、事实校验、完整性检查、实体归一、关系和企业百科。
- **知识探索**：企业百科与关系图谱均可回溯来源资料。
- **权限与审计**：管理员/普通用户、普通用户上传开关、会话管理和关键操作审计。
- **MCP**：向外部 Agent 提供知识检索和来源资料打包能力。

## 架构

```text
Browser
   |
   v
Spring Boot / REST / MCP
   |
   +-- MySQL ------ 资料、版本、元数据、证据、任务、用户、审计
   +-- MinIO ------ 原始文件与完整知识资产
   +-- OpenSearch - 原始知识、摘要、百科的 BM25 + Vector 检索
   +-- Neo4j ------ 规范实体、事件与关系图谱
   |
   +-- MinerU ---------------------- 文档解析（外部服务）
   +-- Chat / Embedding / Rerank -- 模型能力（外部服务）
```

持久化事实保存在 MySQL 与 MinIO；OpenSearch 和 Neo4j 是可重建的派生检索层。详细边界见 [架构说明](docs/ARCHITECTURE.md)。

## 部署要求

本项目自身通过 Docker Compose 部署 MySQL、MinIO、OpenSearch、Neo4j 和应用服务。以下能力由用户提供 endpoint，项目**不会绑定或自动部署指定模型**：

- MinerU Pipeline
- OpenAI-compatible Chat API
- OpenAI-compatible Embedding API（`POST /v1/embeddings`）
- Rerank API（`POST /v1/rerank`）

宿主机建议至少准备 Docker Engine 与 Docker Compose。直接本地开发需要 JDK 17、Maven 3.9+、Node.js 24+ 和 npm。

## 快速开始

```bash
git clone <repository-url> evokb
cd evokb
cp .env.example .env
```

编辑 `.env`，至少替换所有 `[REDACTED_SECRET]`，并填写真实的 MinerU、Chat、Embedding 和 Rerank endpoint/model。

先校验 Compose：

```bash
docker compose -f deploy/docker-compose.yml config
```

如果环境使用独立的旧版 Compose CLI，也可以执行：

```bash
docker-compose -f deploy/docker-compose.yml config
```

启动：

```bash
docker compose -f deploy/docker-compose.yml up -d --build
```

默认仅绑定本机回环地址。启动完成后访问：

```text
http://127.0.0.1:18080/enterprise
```

首次启动且数据库中不存在用户时，页面会引导创建第一个管理员账号；仓库不内置默认管理员或默认密码。

## 配置

基础设施与启动配置使用 `EKB_*` 环境变量。LLM、Embedding、Rerank 也可以在管理员“模型配置”页面维护；页面保存值会持久化并优先用于后续模型调用，因此模型参数不再是首次启动的硬前置条件。常用配置：

| 配置 | 用途 |
| --- | --- |
| `EKB_CHAT_BASE_URL` / `EKB_CHAT_API_KEY` | Chat 服务 |
| `EKB_SMALL_MODEL` / `EKB_LARGE_MODEL` | 小/大模型名称 |
| `EKB_EMBEDDING_BASE_URL` / `EKB_EMBEDDING_MODEL` | Embedding 服务 |
| `EKB_RERANK_BASE_URL` / `EKB_RERANK_MODEL` | Rerank 服务 |
| `EKB_MINERU_URL` | MinerU Pipeline |
| `EKB_MYSQL_*` | MySQL |
| `EKB_MINIO_*` | MinIO |
| `EKB_NEO4J_*` | Neo4j |
| `EKB_SERVER_PORT` | Web/API 端口 |

完整清单、默认值和优先级见 [配置说明](docs/CONFIGURATION.md)。真实 `.env`、本地配置、构建产物和运行数据均被 `.gitignore` 排除。

## 本地开发

后端：

```bash
mvn -f java/pom.xml clean verify
```

`verify` 会运行单元测试、ArchUnit 分层门禁和 Spotless 格式检查。

前端：

```bash
cd web
npm ci
npm run lint
npm run typecheck
npm run build
npm audit --audit-level=low
```

针对一套全新部署还可以运行浏览器 smoke：

```bash
cd web
npx playwright install chromium
EKB_E2E_BASE_URL=http://127.0.0.1:18080 npm run e2e
```

如果机器已经安装 Chrome 或 Edge，也可以通过 `EKB_E2E_BROWSER_CHANNEL=chrome` 或 `EKB_E2E_BROWSER_CHANNEL=msedge` 复用本机浏览器，不需要在仓库中配置浏览器绝对路径。该 smoke 会创建首个管理员，因此应针对空用户数据库运行。

前端开发服务器默认把 `/api` 和 `/mcp` 代理到 `http://127.0.0.1:18080`。可通过 `VITE_API_PROXY_TARGET` 修改；生产构建始终使用同源请求，不写死后端地址。

## 工程结构

```text
java/
  ekb-domain/          领域模型与领域规则
  ekb-application/     用例、编排与端口
  ekb-infrastructure/  MySQL / MinIO / OpenSearch / Neo4j / 模型 / MinerU 适配
  ekb-interfaces/      REST / MCP / Web 安全入口
  ekb-bootstrap/       Spring Boot 组合根
web/                   Preact + TypeScript 前端
deploy/                Dockerfile、Compose、MySQL 初始化脚本
docs/                  架构、配置、部署和安全文档
```

## 文档

- [架构说明](docs/ARCHITECTURE.md)
- [配置说明](docs/CONFIGURATION.md)
- [部署说明](docs/DEPLOYMENT.md)
- [安全说明](docs/SECURITY.md)
- [贡献指南](CONTRIBUTING.md)

## License

MIT，详见 [LICENSE](LICENSE)。
