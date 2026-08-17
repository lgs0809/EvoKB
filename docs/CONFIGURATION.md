# 配置说明

## 配置原则

运行配置统一使用 `EKB_*` 环境变量。仓库只提供 `.env.example`，真实 `.env` 不进入 Git。

Docker Compose 的推荐流程：

```bash
cp .env.example .env
# 编辑 .env
docker compose -f deploy/docker-compose.yml config
docker compose -f deploy/docker-compose.yml up -d --build
```

配置优先级：

1. 启动进程显式传入的环境变量；
2. Docker Compose 读取的 `.env`；
3. `application.yml` 中仅适用于本地开发的安全默认值。

对外部模型、MinerU 和真实 secret，不提供可误用的生产默认值。

## 外部服务

### Chat

| 环境变量 | 必填 | 说明 |
| --- | --- | --- |
| `EKB_CHAT_BASE_URL` | 是 | OpenAI-compatible base URL；允许尾部 `/v1`，运行时会规范化 |
| `EKB_CHAT_API_KEY` | 视 Provider | API Key，支持无需 Key 的自建服务留空 |
| `EKB_SMALL_MODEL` | 是 | 轻量任务模型名 |
| `EKB_LARGE_MODEL` | 是 | 复杂知识生成模型名 |

### Embedding

| 环境变量 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `EKB_EMBEDDING_BASE_URL` | 是 | - | 服务地址 |
| `EKB_EMBEDDING_API_KEY` | 视 Provider | 空 | API Key |
| `EKB_EMBEDDING_MODEL` | 是 | - | 模型名 |
| `EKB_EMBED_DIMS` | 是 | `1024` | 向量维度，必须与模型输出一致 |
| `EKB_EMBED_BATCH_SIZE` | 否 | `16` | 批量请求大小 |
| `EKB_EMBED_TIMEOUT_SECONDS` | 否 | `120` | 请求超时 |

服务需要兼容：

```text
POST /v1/embeddings
```

### Rerank

| 环境变量 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `EKB_RERANK_BASE_URL` | 是 | - | 服务地址 |
| `EKB_RERANK_API_KEY` | 视 Provider | 空 | API Key |
| `EKB_RERANK_MODEL` | 是 | - | 模型名 |
| `EKB_RERANK_TIMEOUT_SECONDS` | 否 | `30` | 超时后回退融合排序 |
| `EKB_RERANK_MAX_CANDIDATES` | 否 | `20` | 最大候选数 |
| `EKB_RERANK_MAX_CHARS` | 否 | `400` | 单候选送入重排的最大文本长度 |

服务需要兼容：

```text
POST /v1/rerank
```

### MinerU

| 环境变量 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `EKB_MINERU_URL` | 是 | - | 已部署 MinerU Pipeline endpoint |
| `EKB_MINERU_POLL_MS` | 否 | `500` | 异步任务轮询间隔 |
| `EKB_MINERU_PARSE_METHOD` | 否 | `auto` | 解析方式 |
| `EKB_MINERU_LANGUAGE` | 否 | `ch` | 默认语言 |

## 持久化与检索

### MySQL

Compose 场景：

- `EKB_MYSQL_DATABASE`，默认 `enterprise_kb`
- `EKB_MYSQL_USER`，默认 `ekb`
- `EKB_MYSQL_PASSWORD`，必填
- `EKB_MYSQL_ROOT_PASSWORD`，仅基础设施初始化使用，必填

直接运行 Java 时还可以显式设置：

- `EKB_JDBC_URL`

### MinIO

- `EKB_MINIO_ENDPOINT`
- `EKB_MINIO_PUBLIC_ENDPOINT`
- `EKB_MINIO_ACCESS_KEY`
- `EKB_MINIO_SECRET_KEY`
- `EKB_MINIO_BUCKET`，默认 `enterprise-kb`
- `EKB_MINIO_REGION`，默认 `us-east-1`

`EKB_MINIO_PUBLIC_ENDPOINT` 用于生成浏览器可以访问的原文件链接，因此它和容器内部 endpoint 可以不同。

### OpenSearch

- `EKB_OPENSEARCH_URL`
- `EKB_RAW_INDEX`，默认 `ekb_raw_v1`
- `EKB_DERIVED_INDEX`，默认 `ekb_derived_v1`

索引不是事实层，可以从 MySQL/MinIO 中的事实重新生成。

### Neo4j

- `EKB_NEO4J_URL`
- `EKB_NEO4J_AUTH`，格式必须为 `username/password`
- `EKB_NEO4J_DATABASE`，默认 `neo4j`

## 服务与安全

- `EKB_SERVER_PORT`：HTTP 端口，默认 `18080`
- `EKB_BIND_ADDRESS`：Compose 主机绑定地址，默认 `127.0.0.1`
- `EKB_SESSION_TTL_HOURS`：会话有效期，默认 `12`
- `EKB_SESSION_COOKIE_SECURE`：HTTPS 部署时应设为 `true`
- `EKB_TRUST_PROXY_HEADERS`：仅在可信反向代理之后启用
- `EKB_WEB_DIR`：前端静态资源目录，本地默认 `./web/dist`
- `EKB_STAGING_ROOT`：批量上传暂存目录

上传限制：

- `EKB_MAX_UPLOAD_BYTES`
- `EKB_MAX_BATCH_UPLOAD_MIB`
- `EKB_MAX_BATCH_EXPANDED_MIB`
- `EKB_MAX_BATCH_FILES`

## 开发前端

Vite 只在开发服务器中使用：

- `VITE_API_PROXY_TARGET`，默认 `http://127.0.0.1:18080`

生产构建请求使用同源 `/api` 和 `/mcp`，因此部署时无需把后端 URL 编译进前端产物。

## Secret 管理

仓库中不得提交真实：

- API Key / Bearer Token
- 数据库密码
- MinIO Secret
- Neo4j 密码
- 私钥或证书密码

`.env.example` 使用占位符。生产环境建议由 Docker secret、Kubernetes Secret、Vault 或平台自己的 secret 管理能力注入环境变量。
