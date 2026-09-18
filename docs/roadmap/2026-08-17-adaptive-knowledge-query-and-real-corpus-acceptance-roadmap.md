# 自适应知识查询与真实语料验收实施计划

更新时间：2026-08-17

目标项目：EvoKB（现有仓库目录 `enterprise-knowledge-base` 保留以兼容本地运行现场）

> 本文保留 2026-08-17 的历史查询设计与验收目标。存储、模型和任务配置记录当时方案，不代表当前部署；当前模块职责见 [后端结构](../../deploy/target/backend-structure.md)，运行与验收状态以 `deploy/target` 下的当前专题文档为准。尚未执行的目标不能作为已通过结果。

---

## 1. 本轮总目标

在当前已经完成产品化、Java DDD 重构、前端改版和公开发布去耦的基础上，完成以下四件事：

1. **先收口上一轮尚未完成的真实外部服务 smoke 和由此暴露的发布缺陷**，确保公开发布版本不是只在 mock/静态测试下成立。
2. 将当前“单轮 Hybrid Retrieval + 单轮回答”升级为 **受约束的自适应多轮知识查询**：简单问题一轮结束，复杂问题自动补充检索，意图不清楚时向用户澄清。
3. 补齐与多轮查询直接相关的产品、持久化、诊断和质量能力，同时完成现有明确未收口的产品后台任务/状态/知识质量能力。
4. 最后从公开可靠来源收集一批真实企业资料，真正执行：
   `上传 → MinerU → Raw → Embedding → OpenSearch → 多轮查询 → Summary → Entity Resolution → Graph → Wiki → Evidence`，并通过浏览器查看 Wiki、Graph 和复杂问答实际效果。

本轮不追求无限自由的通用 ReAct Agent。目标是：

> **有边界、有最大轮数、有证据充分性判断、有澄清状态、可回放、可评测的 Retrieval Agent / Query Orchestrator。**

---

# 2. 固定架构与不可回退原则

以下原则已确定，不重新讨论：

- MinIO：原文件与完整知识资产。
- MySQL：Document / Version / Metadata / Evidence / Task / Query Session 等持久化事实。
- OpenSearch：Raw / Summary / Wiki 的 BM25 + Vector 派生检索层。
- Neo4j：Canonical Entity / Event / Relation Graph。
- MinerU：继续使用现有 Pipeline，不升级 Hybrid High。
- Evidence-first / fail-closed：最终事实必须能回原文证据。
- 每次单轮检索内部仍保持：
  `Raw/Summary/Wiki/Graph → lane-local retrieval → RRF → balanced pool → one Rerank → Evidence Resolver`。
- Wiki / Graph 可以作为复杂问题的导航和中间线索，但**不能绕过 Raw Evidence 成为无依据的最终事实来源**。
- Java DDD 继续保持：domain / application / infrastructure / interfaces / bootstrap，不退回 controller-service-repository 大杂烩。
- 普通用户产品页面继续使用中文产品术语；BM25/RRF/Rerank 等技术术语只出现在管理员检索诊断场景。
- 后端用例统一由现有 Java 五模块承载，解析和模型服务通过明确适配器接入。
- 不把任何真实 API Key、密码、Token 写入仓库或日志。
- 不 push，除非用户在新 Session 明确要求。

---

# 3. 第一阶段：先收口当前真实外部服务 Smoke

这是新 Session 的第一优先级。不要先开发多轮检索，然后把当前未通过的真实主链遗留到最后。

## 3.1 当前已经确认的真实外部服务状态

上一轮已分别进行接口级 smoke：

- MinerU Pipeline：可调用。
- Embedding 服务：可调用，但冷启动/单请求延迟可能超过 120 秒。
- Rerank 服务：可调用，曾观察到明显冷启动延迟。
- `gpt-5.4-mini`：真实 Chat API 可调用。
- `gpt-5.6-luna`：真实 Chat API 可调用。

注意：产品仓库不能硬编码上述模型名；这里仅作为本地验收环境的实际映射。

## 3.2 上一轮真实链暴露并已开始修复的问题

新 Session 开始后先检查当前工作树真实 diff，不要覆盖这些尚未提交的修复。

### A. 可配置 MySQL 数据库名实际上被初始化 SQL 写死

现象：Compose 可设置 `EKB_MYSQL_DATABASE`，但 SQL 原来硬写：

- `CREATE DATABASE enterprise_kb`
- `USE enterprise_kb`

结果是用户换数据库名时，应用连接新库，但表被建在旧库。

当前修复方向：

- 初始化 SQL 不再写死数据库名。
- 依赖 MySQL 官方 entrypoint 对 `MYSQL_DATABASE` 的标准处理。
- GitHub browser smoke 应故意使用**非默认数据库名**，防止以后回归。

新 Session 必须验证：fresh volume + 自定义数据库名时完整 schema 正确初始化。

### B. MinerU `content_list` 中存在无可检索正文的结构块

原逻辑会把所有 ParsedBlock 强制构造成 RawBlock，而 RawBlock 要求 `content` 非空，真实扫描 PDF 会出现：

`RAW_INGEST_FAILED: content is required`

当前修复方向：

- Parser 结果可以保留结构信息；
- 只有实际具有可检索内容的块进入 Raw / Evidence / Chunk；
- 已补 `RawKnowledgeBuilderTest` 回归用例。

新 Session 要确认最终源码、镜像和运行态实际使用了该修复。

### C. 外部 Embedding/Rerank 响应 Content-Type 兼容性

真实模型服务曾出现响应体是合法 JSON、但 Content-Type 异常的情况。

当前修复方向：

- 模型适配器不依赖 Spring `HttpMessageConverter` 按 Content-Type 反序列化；
- 使用底层响应流读取 bytes，再由 Jackson 解析 JSON；
- 已增加模拟 `application/octet-stream + JSON body` 的回归测试。

新 Session 要验证标准 JSON 与异常 Content-Type 两种路径都通过。

### D. Embedding 真机延迟超过默认 120 秒

真实请求曾出现约 121～124 秒量级，导致 `EKB_EMBED_TIMEOUT_SECONDS=120` 正好踩边界。

处理原则：

- 不因为本机模型慢就把模型写死或绕过 Embedding。
- 先在 smoke 环境把 timeout 调到 300 秒完成整链。
- 记录真实冷启动/热请求延迟。
- 再判断公开默认值是否需要调整，或仅在 README/配置文档说明慢模型需提高 timeout。
- 不应让慢模型拖垮全部搜索；Rerank 继续允许受控 fallback，但 Embedding 入库失败必须明确暴露。

## 3.3 必须真正跑通的真实链

使用独立 Compose project 和独立 volume，不污染现有数据：

```text
公开测试 PDF（至少扫描 PDF 1 个）
→ MinIO Original
→ MySQL Document / Version
→ MinerU Pipeline
→ Raw Block
→ Evidence
→ Smart Chunk
→ Embedding（真实）
→ OpenSearch BM25 + Vector
→ Version ACTIVE
→ 查询
→ RRF
→ Rerank（真实）
→ Evidence Resolver
→ Chat（真实）
→ Citation Validation
→ Evidence Source / 原文件可打开
```

至少验证：

- 处理阶段状态正确。
- 文档最终 ACTIVE / 可查询。
- Rerank 被真实调用或明确按设计 fallback，不能悄悄跳过。
- Chat 返回带合法 `[n]` Citation 的回答。
- Citation 指向真实 Evidence。
- Evidence source URL 能返回原始 PDF。
- 结束后删除测试容器、volume、临时 secret/env 和 runtime image。

完成后再跑：

- `mvn verify`
- 前端 lint / typecheck / build / audit
- Compose config
- 正式 Dockerfile build
- release hygiene
- `git diff --check`

然后把真实 smoke 修复按逻辑单独 commit，并同步到 `public-release`，保证 main/public-release 最终 tree 一致。

---

# 4. 第二阶段：将知识查询升级为自适应多轮检索

## 4.1 当前真实状态

当前 `RetrievalApplicationService.search()` 是**一次检索执行器**：

```text
query
→ Raw / Summary / Wiki / Graph
→ 合并候选
→ balanced rerank pool
→ 一次 Rerank
→ Evidence Resolver
→ final results
```

当前 `AskApplicationService.ask()` 是：

```text
question
→ retrieval.search(question) 一次
→ grounded context
→ 一次回答模型
→ Citation 校验
→ 返回
```

当前没有：

- Query Planning
- Query Rewrite
- Query Decomposition
- Evidence Sufficiency Judge
- Retrieval Retry
- Clarification
- Query Session
- Tool loop / Agent loop

## 4.2 目标架构

保留 `RetrievalApplicationService` 作为**稳定的单轮检索执行器**，不要把 Agent 逻辑塞进去。

新增应用层编排能力，建议命名：

`KnowledgeQueryOrchestrator`

职责：

```text
用户问题
  ↓
问题理解 / 是否需要澄清
  ↓
Retrieval Round 1（原始 query 优先）
  ↓
Evidence Sufficiency Judge
  ├─ ANSWER
  ├─ CONTINUE_SEARCH
  └─ ASK_USER
```

复杂问题时：

```text
当前证据
→ 找出 missing information
→ 生成少量 next_queries / sub_questions
→ 下一轮 RetrievalApplicationService
→ Evidence Merge / Dedup
→ 再做 Sufficiency Judge
→ 最多有限轮数
```

## 4.3 不做自由 ReAct，必须有硬边界

默认限制建议：

- `max_retrieval_rounds = 3`
- `max_total_queries = 8`
- 每轮 next queries 有上限，例如 3。
- 同一 query 归一化后不得重复执行。
- evidence pool 按 evidence_id 去重。
- 超过上限后只能：基于已有证据回答 / 明确证据不足 / 向用户澄清，不能无限循环。

这些限制要配置化，但不要暴露成普通用户产品设置。

## 4.4 三态决策模型

内部至少支持：

### `ANSWER`
当前证据已经足够回答。

应输出：

- answerable=true
- covered_facts / answer_outline
- 必要的 evidence ids

### `CONTINUE_SEARCH`
问题明确，但当前证据存在具体缺口。

应输出结构化：

- `missing_information`
- `next_queries`
- 可选 `preferred_lanes`，但不要让 LLM 绕过统一 Retrieval Service 直接操作底层存储。

### `ASK_USER`
用户意图本身无法可靠确定，例如实体歧义、时间范围不明确、问题维度不明确。

应输出：

- clarification_question
- 可选 choices
- session 保持等待状态

不要把“搜不到”一律变成向用户追问。只有**用户信息缺失**才 ASK_USER；知识库缺资料应明确证据不足。

---

# 5. Query Session 与持久化

当前 `/ask` 无状态，需要增加最小但真实的 Query Session。

建议事实模型：

```text
QuerySession
- session_id
- user_id
- original_question
- status: RUNNING / WAITING_USER / ANSWERED / INSUFFICIENT / FAILED
- filters
- created_at / updated_at

QueryTurn
- turn_id
- session_id
- role: USER / ASSISTANT / SYSTEM_EVENT
- content
- created_at

RetrievalRound
- round_id
- session_id
- round_no
- input_queries
- decision
- missing_information
- started_at / finished_at

QueryEvidence
- session_id
- evidence_id
- first_seen_round
- supporting_query
```

可以根据当前项目风格适当简化，但必须实现：

- 用户澄清后能从同一 session 继续；
- 页面刷新后能恢复未完成/已完成查询；
- 不把完整内部推理/COT 持久化；
- 只保存可审计的结构化决策、query、evidence、状态。

数据库迁移要兼容已有 schema，不破坏现有数据。

---

# 6. Query Understanding / Sufficiency Judge 的模型使用

不要硬编码某个模型名称。

通过现有模型角色配置：

- 小模型优先：问题理解、歧义判断、Evidence Sufficiency、next query 生成。
- 大模型只在确实需要复杂综合回答或复杂规划时使用。

本地验收可映射：

- small → `gpt-5.4-mini`
- large → `gpt-5.6-luna`

但仓库只能依赖 SMALL/LARGE role 配置。

模型输出必须是严格结构化 JSON/typed result，并做 schema validation / retry；不要依赖自由文本解析。

不得要求模型输出或保存隐藏 COT。只需要可用的结构化结果：

- intent / ambiguity
- missing information
- next queries
- answerability
- citations

---

# 7. Wiki / Graph 在多轮检索中的正确角色

Wiki / Graph 不只是展示层，也应成为复杂检索的导航层，但必须保持证据闭环。

## Wiki

示例：用户问“过去三年主要风险是什么？”

```text
第一轮 Wiki 命中风险主题
→ 识别诉讼 / 亏损 / 股权质押等主题
→ 下一轮用这些主题回查 Raw / Summary
→ 最终答案引用 Raw Evidence
```

不能直接把 Wiki 文本当最终事实而不校验证据。

## Graph

示例：用户问“A 和 B 有什么关系，什么时候开始，最近是否变化？”

```text
Graph 找到 A → C → B 路径 / relation
→ 提取 relation type / valid_from / valid_to / entity
→ 下一轮回查支撑该关系的 Evidence
→ 必要时再查历史版本
→ 最终回答
```

Graph candidate 必须保留 provenance / evidence ids。

---

# 8. 管理员“检索测试”与普通用户“知识查询”必须继续分开

这是固定产品边界。

## 检索测试

保持**单轮、可解释**：

```text
原始 query
→ 当前 RetrievalApplicationService
→ 各 lane 候选 / RRF / Rerank / Evidence
```

管理员要用它诊断“这一句话当前检索系统会返回什么”，不能偷偷自动改写 5 次 query。

后续可增加真实 trace：

- lane candidate count
- fused score
- rerank score
- evidence resolved / dropped reason

只展示后端真实 trace，不做前端伪造“为什么命中”。

## 知识查询

升级为多轮自适应查询。

普通用户不需要看到全部技术 trace，但应看到合适的产品状态：

- 正在查找相关资料
- 正在补充查找
- 需要你确认一个信息
- 已找到 N 条参考资料

不要出现“Agent step 1 / BM25 / vector”等开发术语。

---

# 9. 前端多轮交互

改造 `知识查询` 页面：

## 基本体验

- 支持同一个 Query Session 的多轮消息。
- 回答继续保留引用编号与参考资料。
- Clarification 作为自然对话消息出现。
- 如果有选项，可显示简洁 choice chips。
- 用户补充信息后继续原 session，不从零创建新问题。

## 运行状态

普通用户只显示产品化状态，例如：

```text
正在查找资料…
正在补充相关信息…
正在核对原文依据…
```

不要显示内部 COT。

## 恢复

- 页面刷新后，已完成 Query Session 可重新打开。
- WAITING_USER session 仍显示上次澄清问题。
- 失败应有明确但产品化的错误说明。

## 历史查询

近期查询从“简单字符串历史”升级为可恢复 Query Session；不要无限保存前端 localStorage 作为事实源。

---

# 10. 现有明确未完成的产品能力一并收口

这些是之前产品计划里已经明确但尚未完成的内容，新 Session 不要遗忘。

## 10.1 资料详情与上传规模化

完善资料详情：

- 资料信息
- 内容预览
- 版本
- 知识生成
- 证据

补：

- 解析内容预览；
- 批量元数据编辑；
- 1000～2000 文件级列表性能/虚拟化；
- 缺失元数据/重复/失败筛选；
- fill-down / copy / template 等批量编辑能力，避免逐文件手工点。

不要破坏已确定的：

- 资料管理 = 管资产
- 用户上传记录 = 管自己的入库过程
- 管理员入库记录 = 管全局入库过程

## 10.2 “知识生成”真正任务化

目前部分 Summary / Graph / Wiki compile API 仍可能是同步长请求，应收敛为：

- durable async task
- `202 + task_id`
- 任务状态持久化
- retry / idempotency
- 页面“知识生成”只显示 Summary / Entity / Graph / Wiki 等高级知识生成，不混入上传解析任务。

资料应有真实独立状态：

- raw_status
- summary_status
- entity_resolution_status
- graph_status
- wiki_status
- wiki_graph_status
- evaluation_status

不能再用 Summary 状态推断 Graph/Wiki 状态。

## 10.3 知识质量

补管理员 `知识质量` 页面，连接现有 evaluation 能力：

- 摘要事实校验
- 摘要完整性
- 实体归一
- 关系校验
- 百科证据覆盖
- retrieval regression
- 用户反馈
- 知识异常

UI 用“评测集/标准答案集”，不要出现 Golden Artifact 产品术语。

## 10.4 查询反馈

查询回答后增加：

“这次查询是否解决了你的问题？”

负反馈至少支持：

- 没找到需要的资料
- 找到的资料不相关
- 资料正确但回答有误
- 回答不完整
- 引用原文有问题
- 企业资料本身冲突

持久化：

- query/session id
- answer id
- evidence ids
- feedback

反馈不能直接修改 Graph/Wiki，只作为质量改进信号。

## 10.5 Wiki / Graph 展示增强

Wiki：

- 阅读优先
- 内联 `[1]` 引用
- 可选可信链路模式
- 版本/变更查看

Graph：

- pan / zoom / drag
- node type styling
- relation filters
- 1/2-hop
- time filter
- node expand
- relation detail
- evidence
- path highlight

禁止一次加载全图。

---

# 11. 真实公开企业资料集构建与最终效果验收

这一阶段必须真的做，不要只用 synthetic fixture。

## 11.1 数据来源原则

使用公开、合法、权威来源，优先：

- 巨潮资讯
- 上交所 / 深交所公开披露
- 上市公司投资者关系/官网披露
- 监管机构公开文件

不要使用需要登录、付费、绕过权限或来源不明的材料。

新 Session 应先联网检索并选择**一个资料丰富、关系较明显、文件容易公开获取的上市公司或一组有明显关系的公司**，不要在代码里写死公司名称。

## 11.2 建议语料规模

为了既能看效果又不做大规模压力测试，目标约：

- 15～30 份文档；
- 以 PDF 为主；
- 尽量包含近 2～3 年不同时间版本；
- 总量控制在合理 smoke / demo 级别，不需要 GB 级。

建议覆盖：

- 年度报告 2～3 年
- 审计报告
- 公司章程
- 股东/实控人/股权结构相关披露
- 董事/高管变更
- 关联交易
- 重大合同
- 重大诉讼/仲裁
- 对外投资/并购
- 资产减值/重大经营事项
- 其他能形成实体关系和时间变化的公告

## 11.3 元数据

导入时真实填写：

- 公司
- 来源
- 文档类型
- 材料分类
- 文档日期
- 必要标签

不要全部用默认空 metadata，否则无法验收 KnowledgeScope。

## 11.4 真正生成高级知识

完成 Raw 入库后，对高价值资料真正执行：

```text
Summary
→ Fact Grounding / Coverage
→ Entity Extraction
→ Entity Resolution
→ Relation Extraction
→ Relation Validation
→ Neo4j Graph
→ Wiki Write
→ Wiki Claim Grounding
→ ACTIVE
```

至少要生成出：

- 多个企业/人员/股东/子公司/事项实体；
- 多种 relation；
- 有时间信息的 relation；
- 数个可阅读 Wiki 页面；
- Wiki Claim 可以回到 Evidence；
- Graph relation 可以回到 Evidence。

如果结果质量差，应分析是：

- MinerU 解析
- Schema/模板
- 实体归一
- 关系抽取
- Wiki 写作
- grounding
- retrieval

哪一层造成，而不是针对一条结果硬修 prompt。

---

# 12. 真实问题集与多轮 A/B 验收

收集数据后，手工基于真实材料设计约 20～30 个问题，不需要 2000 条 benchmark。

至少覆盖：

## 简单事实问题

约 5～8 个：

- 注册资本
- 主要股东
- 某年度收入/利润
- 某公告日期

预期：大多数第一轮直接 ANSWER。

## 多跳关系问题

约 5～8 个：

- A 与 B 的关系是什么？
- 某实控人通过哪些主体控制公司？
- 某关联交易涉及哪些主体？

预期：Graph/Wiki 提供导航，最终回 Raw Evidence。

## 原因/综合问题

约 5 个：

- 某年度利润下降的主要原因是什么？
- 过去两年主要风险发生了什么变化？
- 某重大事项对公司产生了哪些影响？

预期：多轮检索优于单轮。

## 时间变化问题

约 3～5 个：

- 股权关系何时开始/变化？
- 董事人员前后变化？
- 章程版本变化？

## 澄清问题

约 3～5 个，故意包含：

- 人名歧义
- 时间不明确
- “有什么问题”维度不明确

预期：ASK_USER，而不是系统自行猜测。

## 证据不足问题

至少 2～3 个。

预期：明确证据不足，不编造，不无意义追问。

### A/B

对于复杂问题，同时记录：

- 管理员“检索测试”的单轮结果；
- 普通知识查询的多轮最终结果。

至少比较：

- 是否找到答案所需全部证据；
- evidence coverage；
- 是否产生错误事实；
- 检索轮数；
- 实际查询数；
- 响应时间；
- 是否发生不必要的多轮。

不要追求虚假的综合评分，可输出每类问题通过/失败和失败原因。

---

# 13. Wiki / Graph 浏览器最终验收

最后必须实际打开浏览器查看，而不是只查数据库。

至少验收：

## 企业百科

- 页面是否可读；
- 标题/章节是否合理；
- 引用是否自然；
- 点击引用能否打开原文；
- 是否出现明显重复/冲突/无依据 Claim；
- 版本切换是否正常。

## 关系图谱

- 节点数量和类型是否合理；
- 主要实体是否发生错误合并；
- 核心关系是否符合原始材料；
- 时间关系是否正确；
- 1/2-hop 浏览是否可用；
- relation detail / evidence 是否能打开；
- 不出现无证据关系。

必要时保存验收截图/报告到本地 reports，但不要把临时验收截图和大文件默认提交进公开仓库。

---

# 14. 最终“像公开发布前一样”再完整检查一遍

功能完成后，不要马上说完成，必须重复一轮完整发布验收。

## Java

- `mvn clean verify`
- Spotless
- ArchUnit
- 全测试
- package

## Frontend

- `npm ci`
- ESLint
- typecheck
- build
- npm audit

## Docker

- `docker compose config`
- 正式 Dockerfile build
- fresh volumes 启动
- health check
- 首次管理员 setup
- 真正上传/查询

## Browser

Playwright / Chromium：

- 首次管理员
- 首页
- 知识查询
- 多轮补搜
- ASK_USER 澄清
- Citation
- 资料管理
- 上传记录 / 入库记录
- 知识生成
- 企业百科
- 关系图谱
- 检索测试
- 知识质量
- 用户与系统
- 390px 窄屏
- console error
- pageerror
- 非预期 4xx/5xx

## Security / Release hygiene

再次扫描 tracked 文件：

- secret
- 本机绝对路径
- 其他项目名
- 测试模型写死
- `host.docker.internal`
- tmp / target / dist / node_modules / reports
- 测试数据是否误提交

## Git

- 清理所有 smoke 临时容器/volume/image/env；
- 按逻辑分批 commit；
- 每个 commit 尽量可构建；
- `main` 与 `public-release` 最终 tree 对齐；
- 不 push，除非用户明确要求。

---

# 15. 建议提交拆分

可以根据真实实现调整，但不要最后 `fix all`：

1. `fix: close real external service smoke gaps`
2. `feat: add adaptive multi-round knowledge query orchestration`
3. `feat: persist query sessions and clarification flow`
4. `feat: expose multi-round knowledge query experience`
5. `feat: make knowledge generation fully asynchronous`
6. `feat: add knowledge quality and query feedback`
7. `feat: enrich wiki and graph exploration`
8. `test: add real-corpus retrieval and knowledge acceptance`
9. `ci: extend release gates for adaptive query flows`
10. `docs: document adaptive query and corpus acceptance`

不要为了 commit 数量强拆导致中间 commit 编译不过。

---

# 16. 本轮最终验收标准

只有同时满足以下条件才算真正完成：

1. 上一轮真实外部服务 smoke 暴露的问题全部修复并回归。
2. 扫描 PDF 真正走通：MinerU → Embedding → Index → Rerank → Chat → Citation。
3. 简单问题可以一轮回答，不被强制多轮拖慢。
4. 复杂问题能自动执行至少一次有意义的补充检索。
5. 意图不明确时能 ASK_USER，并在用户补充后继续同一 Query Session。
6. 多轮查询始终保留 Evidence-first / citation fail-closed。
7. 检索测试仍是管理员单轮诊断工具，没有被 Agent 逻辑污染。
8. Summary / Graph / Wiki 生成变成真实可管理的后台知识生成过程。
9. 导入真实公开企业资料后，实际生成可阅读 Wiki 和可浏览 Graph。
10. Wiki Claim / Graph Relation 能回原始 Evidence。
11. 至少一组复杂问题显示多轮检索相对单轮检索确实补齐了证据，而不是只增加调用次数。
12. 完成最终 Java / Frontend / Docker / Browser / Security / Git 全量验收。
13. 所有临时数据、secret、测试容器和 volume 清理干净。
14. 公开发布分支仍保持独立、无历史项目耦合。

如果真实公开语料暴露出新的通用性问题，应修通用根因并补测试；不要针对某家公司、某个答案写死规则或 prompt。
