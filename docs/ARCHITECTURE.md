# 架构说明

## 总体边界

Enterprise Knowledge Base 采用 Java DDD 模块化单体。业务能力与基础设施通过应用端口隔离，HTTP/MCP/Web 只作为入口适配器存在。

```text
interfaces
   |
   v
application ---> domain
   |
   v
ports <--- infrastructure

bootstrap = composition root
```

### `ekb-domain`

只包含领域模型、值对象、领域规则和不依赖框架的计算逻辑。

禁止依赖：

- Spring MVC / Controller DTO
- JDBC / MinIO / OpenSearch / Neo4j SDK
- HTTP/模型 Provider SDK
- interfaces 与 infrastructure 模块

### `ekb-application`

负责用例、业务编排、事务语义和端口定义。它可以依赖 domain，但不依赖具体基础设施实现或 Web 协议。

### `ekb-infrastructure`

实现 application 定义的端口：

- MySQL 持久化
- MinIO 对象存储
- OpenSearch 检索
- Neo4j 图谱
- MinerU 文档解析
- Chat / Embedding / Rerank 模型服务

### `ekb-interfaces`

负责系统入口与协议适配：

- REST
- MCP
- 认证与会话
- 上传与下载
- Web 请求边界

接口层将外部请求转换为应用用例输入，不承载领域规则。

### `ekb-bootstrap`

Spring Boot 组合根，只负责装配 Bean、读取运行配置、注册适配器和启动应用。

## 数据职责

### MySQL

持久化事实层，保存资料、版本、业务元数据、证据、处理任务、知识生成记录、用户和审计等不可由检索索引替代的事实。

### MinIO

保存原始文件和完整知识资产。数据库保存对象定位信息，不把大文件正文塞入业务表。

### OpenSearch

派生检索层，保存原始知识片段、摘要和企业百科的 BM25 与向量索引。索引可以从事实层重新生成。

### Neo4j

派生图谱层，保存规范实体、事件和关系。关系保留证据与来源，图谱可以从事实层和知识生成结果重新构建。

## 文档入库链路

```text
上传
 -> MinIO 原文件
 -> MySQL 资料/版本
 -> MinerU 或本地解析
 -> 原始知识片段
 -> Embedding
 -> OpenSearch
 -> 激活版本
 -> 可查询
```

“入库完成”的定义是资料已经可以通过原始知识检索，不要求摘要、图谱或百科已经生成。

## 知识生成链路

```text
可用资料版本
 -> 摘要
 -> 事实校验
 -> 完整性检查
 -> 实体归一
 -> 关系
 -> 企业百科
 -> 质量评估
```

知识生成失败不会让已经完成入库的资料失去基本检索能力。

## 检索链路

```text
Raw / Summary / Wiki / Graph
        |
        v
lane-local retrieval
        |
        v
RRF candidate fusion
        |
        v
Rerank
        |
        v
Evidence Resolver
        |
        v
Answer / Sources
```

证据解析是最终可信边界。无法解析到有效来源的派生内容不能伪装成有证据支撑的最终结果。

## 版本与派生数据

- 资料和版本属于事实层。
- 激活新版本后，旧版本派生摘要/百科可以进入过期状态。
- OpenSearch 和 Neo4j 不作为唯一事实来源。
- 关系自身的业务有效时间与资料版本生命周期是两个不同概念。

## 外部依赖

模型和文档解析服务均通过 endpoint 接入，仓库不绑定指定 Provider 或指定模型：

- MinerU Pipeline
- OpenAI-compatible Chat
- OpenAI-compatible Embedding
- `/v1/rerank` compatible Rerank

这使部署可以按组织自己的网络、模型和合规要求替换外部服务，而不修改核心业务代码。
