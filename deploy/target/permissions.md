# 当前账号权限、MCP 与原件下载批次

本批按最新《项目全解-EvoKB.md》实施，保持 PostgreSQL + Milvus，保持 Luna / Terra 的模型角色。属于 KB-01 / KB-03 / KB-04 的部分交付；没有把任何完整 KB 编号标记为完成。未修改秋招资料、前两个仓库的业务代码或 Git 历史。

最终地址：http://127.0.0.1:18481/enterprise/ask 。最终 JAR SHA-256：`f81b08c38cafb9e349b6f40da742bef5d1a036890e9df17c6df5d89d425a32db`；Flyway V1～V5 已应用。当前测试 reader 故意保留在 clearance=10，便于查看撤权后的页面。原始三个业务样例和新增两个权限样例仍为 ACTIVE，原始字节未被替换。

## 代码与边界

| 子项 | 主要文件 / 实现 |
|---|---|
| 可信身份 | `IdentityDomain`、`IdentityApplicationService`、`JdbcIdentityRepository`、`AuthController`、`AdminPage`：读取当前账号 clearance，管理员角色不自动提升文档密级 |
| 同一权限入口 | `KnowledgeAccessService`：客户端 / 历史 filters 不授予权限；检索、模型输入、交付、历史、下载共享当前资料权限；核验 ALL 实际输入来源 |
| 当前来源 | V3 `kb_source_authority`、`JdbcProjectionAuthority`、`JdbcKnowledgeRepository`、`PostgresGraphStoreAdapter`：ACTIVE、当前文档密级、ACL；新版分类保护仍 ACTIVE 的旧版本 |
| 真正的构建输入 | V4 `kb_asset_build_dependency`、`WikiCompilationService`、`JdbcCompilationRepository`：记录全部输入 Summary 对应来源，包含被模型省略、没有出现在最后引用里的资料；缺失输入在事务内回滚 |
| 查询 / 历史 | `KnowledgeQueryOrchestrator`、`JdbcQuerySessionRepository`、`QueryHistoryApplicationService`、`QueryHistoryPage`：记录实际输入依赖；撤权后屏蔽答案、引用和下载；原始持久化回答保留，权限恢复时重新校验 |
| 原件 / ZIP | `ObjectStoreSourcePackageService`、`JdbcPackageAuthorizationRepository`、`EnterpriseApiController`：应用登录态下载、账号绑定、当前来源复核、no-store；不对客户端发出 MinIO 签名 GET 链接 |
| 安全预览 | `SourceDownloadHeaders`、`EvidenceSourceViewer`：原件按附件返回，nosniff + sandbox；2 MiB 以内文本在页面用纯文本预览，PDF 仍使用既有 PDF.js 获取字节；未认证扫描 PDF 解析 |
| MCP | `EnterpriseMcpAuthenticationFilter`、`McpIdentityContext`、`EnterpriseMcpTools`、`JdbcMcpSessionRepository`、V5、`EnterpriseConfiguration`：每个请求登录校验，协议会话归属账号，框架 transport context 携带身份，ToolContext 不进入模型参数 Schema |
| 控制接口 | `EnterpriseCompilationController`、`EnterpriseEvaluationController`、`ArtifactEvaluationApplicationService`、`JdbcArtifactEvaluationRepository`：全部输入校验；评估在同一 PG 快照读取资产 / 真实依赖，返回前再复核当前权限 |
| 列表 | `SourceAccessSql`、`JdbcEnterpriseOperations`：Wiki、已覆盖任务 / 质量 / 审计、直接资料读取在数据库 count / LIMIT 前过滤 |
| 部署 / 验证 | `scripts/deploy-target.py`、`record-backend-verification.py`、`test-target-permissions.py`、`test-target-mcp-access.py`、`SourceAccessProbe.java`、`test-postgres-source-access.py`；部署只接受完整校验对应的源码和不可变 JAR |

MCP 使用现有 Spring AI / SDK 的 Streamable HTTP 与 contextExtractor，没有用模型参数或 ThreadLocal 作为身份来源。`/mcp` 使用与页面相同的登录 Cookie；session ID 不是凭据。跨账号 / 已过期协议 session 返回 404，匿名返回 401，跨 Origin 返回 403。

MCP 不提供 GET 事件重放流，返回 405；新 POST 调用重新检查当前权限，防止 SDK 缓存的旧内容绕过撤权。这是 [Streamable HTTP 规范允许的模式](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)，并非关闭 MCP 工具。框架接入依据：[Spring AI MCP Server](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)；实现 API 另外核对了本机 1.1.2 / SDK 0.17.0 的实际依赖。

## 可重跑步骤

每次使用新日志 / 报告路径；输出已存在时脚本拒绝覆盖。启动 / 构建方式见同目录 README。下面在仓库根目录执行。

```sh
mvn -B -f java/pom.xml clean verify > .tmp/target/permission-manual-01.log 2>&1
python3 scripts/record-backend-verification.py --log .tmp/target/permission-manual-01.log --output .tmp/target/permission-manual-01.json
python3 scripts/deploy-target.py build-web --log .tmp/target/permission-web-manual-01.log
python3 scripts/deploy-target.py stop
python3 scripts/deploy-target.py start --verification .tmp/target/permission-manual-01.json
python3 scripts/test-target-permissions.py seed --output .tmp/target/permission-seed-manual-01.json
python3 scripts/test-postgres-source-access.py --output .tmp/target/source-access-manual-01.json
python3 scripts/test-milvus-components.py --output .tmp/target/milvus-access-manual-01.json
python3 scripts/test-postgres-graph.py --output .tmp/target/graph-access-manual-01.json
python3 scripts/test-target-mcp-access.py --output .tmp/target/mcp-access-manual-01.json
```

三份公开业务样例在 `deploy/acceptance/target-fixtures/`；权限样例在 `deploy/acceptance/permission-fixtures/`。全部明确标为虚构验收数据。seed 通过正常上传 / 分类 API，校验同名文档 SHA；冲突拒绝，重复导入复用 ACTIVE 版本。测试账号凭据保留在 Git 忽略的 `.tmp/target/permission-accounts.json`，权限 0600，不放入报告 / 截图。

手动测试只需正常登录和自然语言提问。以下两个命令只通过正常管理员 API 调整本批专用虚构 reader，核对用户名和 ID，不修改其他账号、文档或任务状态：

```sh
python3 scripts/test-target-permissions.py reader-grant --output .tmp/target/manual-reader-grant-01.json
# 使用 acceptance_permission_confidential 登录，在页面直接提问、查看原文并下载。
python3 scripts/test-target-permissions.py reader-revoke --output .tmp/target/manual-reader-revoke-01.json
# 刷新同一问题、历史记录和查询组，核验无原答案 / 引用、下载按钮禁用；旧 URL 也返回拒绝。
```

提问示例：

> 星桥 HT-ACL-031 采购合同验收并收到发票后，尾款在几个工作日内支付？尾款比例和金额是多少？请引用原文。

高权限应从原文回答 17 个工作日、37%、44.4 万元。PUBLIC 账号只能读取含总价 120 万元的公开索引，不能从客户端伪造 clearance 读保密付款条款。

浏览器完成真实查询后，在以下命令填入实际 query UUID。`--related-session` 可指定第二次真实查询，覆盖查询组缓存包。该测试会把专用 reader 留在 clearance=10；分类 30→40→30 与专用管理员的临时 clearance 都通过正常 API 恢复。

```sh
python3 scripts/test-target-permissions.py verify --session <真实查询UUID> --related-session <另一次真实查询UUID> --output .tmp/target/revocation-manual-01.json
python3 scripts/test-target-api.py --session 3602ec1b-bf15-434e-8f00-369f1c22badb --batch aad75309-8d57-4cd6-89b1-76ff1be8f345 --output .tmp/target/public-regression-manual-01.json
python3 scripts/seed-target-business-data.py --output .tmp/target/seed-replay-manual-01.json
docker exec -i evokb-target-postgres-1 psql -X -v ON_ERROR_STOP=1 -U evokb_target -d evokb_target < deploy/acceptance/permission-runtime-inspection.sql
```

MCP 测试和撤权测试要顺序运行，二者会调整同一个专用 reader；不要并发执行。组件脚本使用单独 schema / Milvus database，保留 SQL 和资源；其中合成编译状态属于明确标注的组件夹具，不算真实生成流程完成。

## 实际结果

证据目录：`$LOCAL_EVIDENCE_ROOT`。该目录的 `README-permissions.md` 和 `permission-batch-manifest.json` 索引完整改动及每次尝试，原报告不覆盖。

| 状态 | 最新证据 | 结果 |
|---|---|---|
| 通过 | `permission-backend-verify-7.json` | 161 项：160 通过、1 跳过；0 failure / error；绑定最终源码与 JAR |
| 通过 | `permission-web-release-5.json` | lint、TypeScript、Vite 构建及资产原子发布 |
| 通过 | `permission-milvus-components-3.json` | 28 项；真实 Milvus / PostgreSQL / Qwen |
| 通过 | `permission-graph-components-3.json` | 15 项；真实 PostgreSQL 图谱 |
| 通过 | `permission-source-components-8.json` | 21 项；ALL 构建输入、权限先于 count / LIMIT、评估快照、协议会话归属 / 过期 |
| 通过 | `permission-mcp-2.json` | 30 项；真实 MCP 初始化 / 工具调用、PG / Milvus / Qwen、原件 ZIP 字节、跨账号 / 伪造元数据 / 撤权 / 事件重放拦截 |
| 通过 | `permission-revocation-3.json` | 39 项；来源分类与账号撤权、历史 / 查询组缓存，12 个并发旧 URL 均 403；原始答案未改写 |
| 通过 | `permission-public-regression-4.json` | 25 项；原公开合同问答、引用、实际 ZIP 与管理 API 回归 |
| 通过 | `permission-original-seed-replay-2.json` | 4 项；原三个样例全部复用，五个来源行和原始字节保持 |
| 通过 | CUA 截图及 `permission-cua-download-3.json` / `permission-cua-original-download-1.json` | 自然语言新问题真实执行、原文预览、中文原件 / ZIP 实际下载、字节校验、反馈落库、撤权后答案 / 引用 / 下载屏蔽 |
| 通过 | `permission-runtime-inspection-2.txt` | 保留的只读 SQL 实际执行；V1～V5、真实来源、实际问题 / 依赖 / 包授权 / 已关闭 MCP 会话、原入库任务 |
| 失败后修复 | 格式检查及部分验证脚本早期尝试 | 原日志均保留。SourceAccess 早期失败为夹具 / 构建 harness 错误；revocation-1 为返回字段名错误；inspection-1 为误写表名。修复后重新执行，没有把失败计为通过 |
| 跳过 / 未测 | Java `RealModelSmokeTest` | 开关未设置，因此跳过不计通过。真实浏览器新查询独立证明了当前问答调用；该行不证明完整 Summary / Concept / Wiki 生成与 Terra 全闭环 |
| 未完成 / 未测 | 完整项目验收 | 见下一节。当前正向模型新查询 `e900ca47-6f4a-4440-9bfe-9ba399c250f0` 执行于 `3a3c8049…`；最后的源文件安全头改动之后，在 `f81b08c3…` 重新验证预览 / 下载 / 撤权，未把旧问题回放混称新模型调用 |

## 明确未完成

- KB-01 / KB-02：Stream + Redisson、600 秒固定数据库 deadline、owner / epoch / 来源 / ACL 发布栅栏、XADD 缺口、PEL 恢复、终态 ACK 全链路。
- KB-03：`kb_asset_source` 的增量来源目录与本批真实构建依赖分别管理；成功且完整下载才记使用信号、独立查询去重、配额 / 预算、选择性自动沉淀、稳定 Concept 推进、Graph 编译新版 scope 与 UI。旧 search hit 计数不能算成功下载计数。
- KB-04：8 轮派发前持久化预算、实际 tokenizer 的 600 / 900 / 80 分块、MinIO 原生 multipart、正式 240 问与 12 上传 / 12 图谱故障矩阵。
- MinerU 3.4.5 常驻服务、扫描 PDF / 表格真实解析仍未部署和验收；原件预览不证明解析链路。
- 完整并发撤权 / 后台发布故障矩阵、未覆盖的管理元数据入口、空环境创建与升级路径仍需继续。当前授权包到期会按不可访问包处理，自动重生成路径存在，但精确的“到期 / 撤权”状态区分尚未专门验收。

本批没有新增外部模型凭据阻塞；未完成项不计为通过。保留既有工作区修改、数据库、数据卷、旧环境、失败证据和不可变部署产物；未执行 reset / force push / 生产部署。
