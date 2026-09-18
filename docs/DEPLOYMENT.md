# 本地部署

当前默认入口为 PostgreSQL / Redis / Milvus / MinIO，所有宿主端口绑定 `127.0.0.1`，默认项目名 `evokb-local`。新环境独立于历史和 target 验收卷，不迁移或清空已有业务数据。

```sh
cp .env.example .env
# 编辑本地密码、已批准的外部解析与模型配置。
make compose-config
make up
# http://127.0.0.1:18080/enterprise
make logs
make stop
```

`stop` 保留容器和卷。首次启动通过页面初始化管理员。未接入外部模型时可管理和浏览，解析、编码和问答明确报告依赖不可用。模型服务和冻结 tokenizer 资产须显式配置，应用镜像不下载模型权重。

详细端口、配置、CI 范围见 [默认部署合同](../deploy/default-deployment.md)。该默认拓扑已做 Compose 静态检查，尚未在本机创建新空环境完成启动验收；已有 target 环境已实际运行，步骤、关联种子资料、只读 SQL 及旧失败记录见 [本地验收环境](../deploy/target/README.md)。

## 开发与产物

后端 `python3 scripts/verify-backend.py --output .tmp/target/backend-verify-manual-01.json` 完整验证后，target 启动脚本核对源码、JAR 和依赖再正常启动。每次使用新报告路径，失败报告原样保留。前端发布先写带 Hash 的资产，最后原子替换入口，保留已打开页面需要的旧资产。

历史拓扑 `deploy/docker-compose.legacy.yml` 使用历史工件和独立卷，仅用于排查。不要将当前 PostgreSQL 应用连接到历史数据库，不执行 `down -v`，不把健康检查当作业务完成。
