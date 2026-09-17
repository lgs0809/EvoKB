# 贡献指南

感谢参与 EvoKB。

## 开发环境

- JDK 17
- Maven 3.9+
- Node.js 24+
- npm
- Docker Engine + Docker Compose（需要做集成验证时）

## 代码边界

后端保持 DDD 模块边界：

- `ekb-domain`：纯领域模型与规则，不依赖 Spring Web、数据库或第三方适配器。
- `ekb-application`：用例、业务编排、事务语义和端口。
- `ekb-infrastructure`：数据库、对象存储、检索、图谱、模型和 MinerU 适配器。
- `ekb-interfaces`：REST、MCP、认证、上传等协议入口。
- `ekb-bootstrap`：组合根和启动配置。

不要为了形式化 DDD 增加没有领域意义的 Factory/Manager/Coordinator/Facade。

## 提交前检查

后端：

```bash
mvn -f java/pom.xml clean test package
```

前端：

```bash
cd web
npm ci
npm run lint
npm run typecheck
npm run build
npm audit --audit-level=low
```

Docker：

```bash
docker compose -f deploy/docker-compose.yml config
```

## 配置与 Secret

- 新配置统一使用 `EKB_*`；前端开发专用变量使用 `VITE_*`。
- 不提交真实 `.env`。
- 不写入开发者绝对路径、固定个人账号、私有 endpoint 或 API Key。
- 模型 Provider 和模型名必须可配置，不把本地测试模型变成产品依赖。
- 新增配置时同步 `.env.example`、Compose 和 `docs/CONFIGURATION.md`。

## 前后端协议

修改 REST/MCP DTO 时，同时检查：

- Java interfaces 层；
- application use case；
- `web/src/lib/api.ts`；
- 对应页面；
- 兼容已有持久化协议的必要性。

已经形成外部兼容协议的字段不要仅为了命名美观直接删除。新代码使用新术语时，可以在协议边界保留兼容映射。

## 测试

修复问题应优先添加能够复现问题的测试。涉及 DDD 边界时更新 ArchUnit 规则；涉及批量上传、解析或权限时覆盖失败和越权路径。

## 提交

一个提交尽量表达一个完整变化，并保持可构建。例如：

```text
refactor: decouple runtime configuration
refactor: remove legacy project dependencies
build: make docker deployment portable
ci: add release quality gates
docs: prepare public documentation
```

不要提交生成的 `target/`、`web/dist/`、`node_modules/`、本地报告或真实凭据。
