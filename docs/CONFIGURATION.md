# 配置说明

## 配置原则

基础设施与启动配置使用 `EKB_*` 环境变量。LLM、Embedding、Rerank 还支持管理员登录后在“模型配置”页面维护；页面保存值持久化到 MySQL，并优先于环境变量作为后续模型调用的运行时配置。仓库只提供 `.env.example`，真实 `.env` 不进入 Git。

Docker Compose 的推荐流程：

```bash
cp .env.example .env
# 编辑 .env
docker compose -f deploy/docker-compose.yml config
docker compose -f deploy/docker-compose.yml up -d --build
```

配置优先级：

1. 管理员“模型配置”页面已保存的 LLM / Embedding / Rerank 配置；
2. 启动进程显式传入的对应 `EKB_*` 环境变量（作为尚未保存页面配置时的初始默认值）；
3. Docker Compose 读取的 `.env`；
4. `application.yml` 中仅适用于本地开发的安全默认值。

模型配置可以在首次登录后补齐，因此 LLM / Embedding / Rerank 不再是服务启动的硬前置条件；真正执行问答、向量化或重排时仍会 fail-closed 校验所需配置。MinerU 和基础设施 secret 仍不提供可误用的生产默认值。

## 外部服务

### Chat

| 环境变量 | 必填 | 说明 |
| --- | --- | --- |
| `EKB_CHAT_BASE_URL` | 否 | OpenAI-compatible base URL；未在页面保存配置时作为初始默认值 |
| `EKB_CHAT_API_KEY` | 否 | API Key；无需 Key 的自建服务可留空，页面不会回显已保存 Key |
| `EKB_SMALL_MODEL` | 否 | 轻量任务模型名；未配置时相关调用会明确失败 |
| `EKB_LARGE_MODEL` | 否 | 复杂知识生成模型名；未配置时相关调用会明确失败 |
| `EKB_CHAT_TIMEOUT_SECONDS` | 否 | Chat 请求超时，默认 `120` 秒 |
| `EKB_ASSISTANT_FALLBACK` | 否 | 编译链的证据式本地 fallback：`disabled`（默认）、`on-failure` 或 `always`；不影响普通 Ask，且产物会标记来源 |

### Embedding

| 环境变量 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `EKB_EMBEDDING_BASE_URL` | 否 | 空 | 服务地址；可在管理员页面配置 |
| `EKB_EMBEDDING_API_KEY` | 否 | 空 | API Key；页面不会回显已保存 Key |
| `EKB_EMBEDDING_MODEL` | 否 | 空 | 模型名；未配置时向量化明确失败 |
| `EKB_EMBED_DIMS` | 否 | `1024` | 向量维度，必须与模型输出和 OpenSearch 索引一致 |
| `EKB_EMBED_BATCH_SIZE` | 否 | `16` | 批量请求大小 |
| `EKB_EMBED_TIMEOUT_SECONDS` | 否 | `120` | 请求超时 |
| `EKB_EMBED_MAX_CONCURRENCY` | 否 | `1` | 同时执行的 Embedding 调用链数量；本地 CPU 模型建议保持 `1`，外部 API 可按服务限流提高 |

服务需要兼容：

```text
POST /v1/embeddings
```

### Rerank

| 环境变量 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `EKB_RERANK_BASE_URL` | 否 | 空 | 服务地址；可在管理员页面配置 |
| `EKB_RERANK_API_KEY` | 否 | 空 | API Key；页面不会回显已保存 Key |
| `EKB_RERANK_MODEL` | 否 | 空 | 模型名；未配置/调用失败时按受控 fallback 处理 |
| `EKB_RERANK_TIMEOUT_SECONDS` | 否 | `30` | 超时后回退融合排序 |
| `EKB_RERANK_MAX_CONCURRENCY` | 否 | `1` | 同时执行的 Rerank 调用数量；外部 API 可按服务配额提高 |
| `EKB_RERANK_MAX_CANDIDATES` | 否 | `20` | 最大候选数 |
| `EKB_RERANK_MAX_CHARS` | 否 | `400` | 单候选送入重排的最大文本长度 |

服务需要兼容：

```text
POST /v1/rerank
```

### 管理员模型配置页面

管理员可以在“模型配置”页面同时维护 Chat、Embedding 和 Rerank。保存后无需重启应用，后续模型调用会读取最新有效配置；API Key 只接受写入，不会通过读取接口回显。留空 Key 表示保留已保存值，只有显式选择清除才会删除。

Embedding 的模型或维度发生变化时，必须同步重建使用旧维度的 OpenSearch 派生向量索引，否则索引映射与新向量会不兼容。

### MinerU

| 环境变量 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `EKB_MINERU_URL` | 是 | - | 已部署 MinerU Pipeline endpoint |
| `EKB_MINERU_POLL_MS` | 否 | `500` | 异步任务轮询间隔 |
| `EKB_MINERU_TASK_TIMEOUT_SECONDS` | 否 | `14400` | 单个 MinerU 异步任务最长等待时间；超时后当前文件失败并继续队列（MinerU 自身的页面渲染超时仍会按解析器错误失败） |
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
