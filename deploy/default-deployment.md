# 默认本机部署入口

`docker-compose.yml`对应当前 PostgreSQL / Redis Streams / Milvus / MinIO 与Java应用。Milvus standalone 使用 etcd 与同一MinIO中的独立桶。宿主端口固定绑定loopback，容器内应用监听私有网络。默认项目名`evokb-local`与历史、target验收隔离，Flyway由应用初始化新PG库；不导入旧MySQL初始化脚本。

```sh
cp .env.example .env
# 编辑本地密码、已获准的外部服务地址与tokenizer配置。
make compose-config
make up
# 浏览器访问 http://127.0.0.1:18080，首次通过页面初始化管理员。
make logs
make stop
```

`make stop`仅停止容器，保留数据。启动会构建应用及使用声明的基础设施镜像，不下载MinerU/Chat/Embedding模型权重。聊天固定使用`gpt-5.6-luna`与`gpt-5.6-terra`；Raw冻结Qwen3-VL-Embedding-2B1024模型/tokenizer，必须提供已批准快照的六资产与manifest。MinIO公开地址和浏览器Origin需与本机端口相符；应用内部外部服务地址必须从容器可达，不能把宿主127.0.0.1当成容器地址。

未配置外部端点时仍可浏览、初始化账号和管理资料；解析或模型任务明确失败，不能据此宣称完整入库/问答通过。没有tokenizer manifest时Raw显式报TOKENIZER_UNAVAILABLE，不启用字符估计或模型替换。MinerU保持3.4.5/hybrid-engine/medium/auto与600秒，单文件有效上限256MiB。

`docker-compose.legacy.yml`与`legacy.env.example`只保留旧MySQL/OpenSearch/Neo4j拓扑；project ID、卷名、挂载和原字节保持，当前默认构建不再使用它。历史应用需对应历史工件，不能用当前PG应用冒充旧栈兼容。此整理没有启动、重建或删除原容器。`docker-compose.target.yml`和target私密配置/运行脚本也保持不变。

CI的默认Compose校验和浏览器smoke使用当前PG拓扑。浏览器smoke仅初始化与页面浏览，不调用真实模型；CI在独立临时项目中生成本地随机密码，清理只涉及该CI项目。Docker镜像构建和CI真实启动仍需在对应CI环境执行；本机结构整理仅做Compose配置与启动合同静态验证，不标记新部署通过。
