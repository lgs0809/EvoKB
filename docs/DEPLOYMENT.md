# 部署说明

## 1. 准备外部服务

部署应用之前至少准备 MinerU Pipeline endpoint。Chat、Embedding、Rerank 可以提前通过环境变量提供，也可以在系统首次启动、创建管理员后进入“模型配置”页面补齐。

这些服务可以部署在同一台机器、局域网或云端，只要应用容器能够访问。仓库不内置指定模型服务。

## 2. 准备配置

```bash
cp .env.example .env
```

修改 `.env`，至少补齐基础设施密码/凭据和 `EKB_MINERU_URL`。如果希望应用启动后立即具备模型能力，也可以预先填写 Chat、Embedding、Rerank 的 `EKB_*` 配置；否则登录后在管理员“模型配置”页面保存即可。

不要把 `.env` 提交到 Git。

## 3. 校验 Compose

Docker Compose v2：

```bash
docker compose -f deploy/docker-compose.yml config
```

独立 Compose CLI：

```bash
docker-compose -f deploy/docker-compose.yml config
```

出现未展开的必填变量或配置错误时，不要继续启动。

## 4. 构建并启动

```bash
docker compose -f deploy/docker-compose.yml up -d --build
```

Compose 会创建：

- `mysql`
- `minio`
- `opensearch`
- `neo4j`
- `app`

应用镜像在构建阶段同时完成前端 production build 和 Java package，运行镜像只包含 JRE、可执行 jar 和静态资源，不依赖宿主机 JDK/Node/Maven。

## 5. 健康检查

```bash
curl http://127.0.0.1:18080/health
```

正常响应示例：

```json
{"status":"ok","implementation":"java-ddd"}
```

随后访问：

```text
http://127.0.0.1:18080/enterprise
```

首次运行创建管理员。

## 6. 网络暴露

默认 `EKB_BIND_ADDRESS=127.0.0.1`，数据库、对象存储和应用仅监听宿主机回环地址。

需要向其他机器提供服务时：

1. 把应用放在 HTTPS 反向代理后；
2. 将 `EKB_SESSION_COOKIE_SECURE=true`；
3. 只有在代理受信任时才设置 `EKB_TRUST_PROXY_HEADERS=true`；
4. 按需开放应用端口，不建议直接公开 MySQL、MinIO、OpenSearch 和 Neo4j 管理端口；
5. 使用防火墙或容器网络进一步限制基础设施访问。

## 7. 数据卷

Compose 使用命名卷：

- `mysql-data`
- `minio-data`
- `opensearch-data`
- `neo4j-data`
- `server-staging`

其中 MySQL 和 MinIO 是主要事实层。备份优先覆盖二者。OpenSearch 与 Neo4j 可以从事实数据重新构建，但实际恢复演练仍应按组织要求验证。

## 8. 升级

升级前建议：

1. 备份 MySQL 与 MinIO；
2. 查看版本说明；
3. 拉取代码；
4. 执行自动测试；
5. 重新构建应用镜像；
6. 观察 `/health` 与应用日志。

示例：

```bash
git pull
mvn -f java/pom.xml test
cd web && npm ci && npm run build && cd ..
docker compose -f deploy/docker-compose.yml up -d --build
```

## 9. 本地直接运行

如果不通过 Docker 运行应用，先自行提供 MySQL、MinIO、OpenSearch、Neo4j 和外部模型/解析服务，并导出对应 `EKB_*` 变量。

```bash
cd web
npm ci
npm run build
cd ..
mvn -f java/pom.xml clean package
java -jar java/ekb-bootstrap/target/evokb.jar
```

默认前端静态目录为 `web/dist`。
