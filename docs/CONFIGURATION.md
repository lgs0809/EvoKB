# 配置说明

当前配置以 [.env.example](../.env.example)、[默认 Compose](../deploy/docker-compose.yml) 与 `java/ekb-bootstrap/src/main/resources/application.yml` 为准。真实 `.env`、`.env.target` 和账号记录被 Git 忽略。

| 范围 | 主要配置 | 当前约定 |
| --- | --- | --- |
| PostgreSQL | `EKB_POSTGRES_DATABASE/USER/PASSWORD`、`EKB_DB_POOL_SIZE` | Compose 映射为 JDBC/DB 环境变量；事实、权限、任务与图谱 |
| Redis | `EKB_RAW_STREAM`、`EKB_RAW_STREAM_GROUP` | Raw 调度及 PEL；任务完成以 PG 账本为准 |
| Milvus | `EKB_MILVUS_COLLECTION`、`EKB_MILVUS_TOKEN` | `knowledge_chunks`，1024 维；生产事实不由向量索引替代 |
| MinIO | `EKB_MINIO_ROOT_USER/PASSWORD`、`EKB_MINIO_BUCKET` | 原件/产物与 Milvus 独立桶；公开对象地址须与浏览器可达地址一致 |
| MinerU | `EKB_MINERU_URL`、`EKB_MINERU_LANGUAGE/POLL_MS` | 3.4.5、hybrid-engine、medium、auto；600 秒、单文件 256 MiB |
| Chat | `EKB_CHAT_BASE_URL/API_KEY`、`EKB_CHAT_TIMEOUT_SECONDS` | 小模型 Luna / 大模型 Terra；默认部署固定既定模型 |
| Embedding | `EKB_EMBEDDING_BASE_URL/API_KEY`、`EKB_EMBED_TIMEOUT_SECONDS/MAX_CONCURRENCY` | Qwen3-VL-Embedding-2B，1024 维；默认并发 1 |
| Rerank | `EKB_RERANK_BASE_URL/API_KEY`、`EKB_RERANK_TIMEOUT_SECONDS/MAX_CONCURRENCY` | Qwen3-VL-Reranker-2B；默认 30 秒、并发 1 |
| Tokenizer | `EKB_TOKENIZER_ASSETS_DIR`、`EKB_EMBED_TOKENIZER_MANIFEST` | manifest 与六个校验资产；缺失时报 TOKENIZER_UNAVAILABLE，不用字符估计 |
| 安全/上传 | `EKB_SESSION_TTL_HOURS`、`EKB_SESSION_COOKIE_SECURE`、`EKB_MAX_BATCH_*` | 会话默认 12 小时；单文件有效上限 256 MiB |

模型页面保存的有效配置持久化到 PostgreSQL，并作为后续调用的运行配置；API Key 只写不回显，空白输入保留旧 Key，清除需明确动作。Chat、Embedding、Rerank 支持管理员在页面补齐；缺配置不影响普通页面启动，真正执行时 fail-closed。端点须从应用所在容器或进程可达，宿主 `127.0.0.1` 不能当成容器的宿主地址。

改变编码身份、模型、维度或 tokenizer 时必须遵守既定迁移与重建流程；不追补旧缓存认证，也不混用已认证和未认证向量。当前模型配置不构成任意静默迁移授权。

前端开发 `VITE_API_PROXY_TARGET` 默认指向 `http://127.0.0.1:18080`；生产使用同源 `/api` 与 `/mcp`。默认 Compose 宿主绑定回环地址，`EKB_SERVER_HOST=0.0.0.0` 仅是容器内监听。仓库不提供公网部署步骤。

[默认部署](../deploy/default-deployment.md) 与 [target 环境](../deploy/target/README.md) 各用独立配置和数据；启动前核对目标。真实密钥、数据库密码、对象存储凭据、个人路径和账号文件不进入 Git。
