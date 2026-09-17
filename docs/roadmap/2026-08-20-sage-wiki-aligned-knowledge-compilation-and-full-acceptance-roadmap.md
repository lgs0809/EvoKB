# Sage-Wiki 对齐的知识编译与全量验收路线图

更新时间：2026-08-20

目标项目：EvoKB（现有仓库目录 `enterprise-knowledge-base` 保留以兼容本地运行现场）

> 本文从 2026-08-20 起作为知识编译链的权威实施方案。继续保留 2026-08-17 路线图中已经确定的 Raw Retrieval、自适应查询、产品化和真实语料验收要求；本文件重点修正 Summary / Concept / Graph / Wiki 的编译模型、任务 DAG、跨文档 Wiki 和最终验收顺序。

---

## 1. 固定原则

1. Raw Evidence 是事实源；Summary / Concept / Graph / Wiki 都是可重建派生层。
2. 最终回答必须回到 Raw Evidence，Wiki/Graph 只能导航、组织和加速复杂检索。
3. 参考 Sage-Wiki 的分层思想，但不照搬 Go/SQLite/存储实现，不重新引入 Sage-Wiki 运行时依赖。
4. Summary、Concept、Graph、Wiki **四层都有 Prompt Template**；Prompt Template 与业务 Schema 必须分开理解。
5. Summary：内容驱动，可有通用/按文档类型选择的轻量 Prompt，不做固定企业字段模板。
6. Concept：独立 Concept Extraction Prompt，输出共享语义锚点；不做重业务表单模板。
7. Graph：使用严格 Entity / Relation vocabulary 和确定性校验；每条 relation 必须可回到 fact/evidence。
8. Wiki：使用 Wiki Writer Prompt，自然组织 section，不固定章节；每个 claim 必须回到 grounded fact/evidence。
9. Graph 与 Wiki 不存在硬前后依赖；二者共同消费 Summary/Concept 结果。
10. Entity Resolution / Reconciliation 是收敛过程，不应让 Graph 或 Wiki 成为彼此前置条件。
11. 一条非法 Graph relation 不应拖垮整批合法 relation；无证据、非法类型、非法 predicate、self-loop 等 candidate 单条拒绝并记录原因。覆盖率仍必须 fail-closed。
12. Wiki 必须从“一文档覆盖一页面”升级成“文档贡献 + 跨文档 materialization/revision”。
13. 文档 Raw ACTIVE 与高级知识生成状态分离：Graph/Wiki 失败不能把已经可检索的文档标成 FAILED。
14. 所有后台任务必须 durable、幂等、可恢复、可局部 retry。
15. 不针对万科、某个答案或单条结果写死规则。

---

## 2. 目标编译 DAG

```text
Original File
    ↓
MinerU / Parser
    ↓
Raw Block → Evidence → Smart Chunk → Embedding/OpenSearch
    ↓
Document Raw ACTIVE
    ↓
SUMMARY
    ↓
Grounded Facts
    ↓
CONCEPT
   /     \
  ↓       ↓
GRAPH    WIKI
  \       /
   \     /
  RESOLUTION / RECONCILIATION
```

关键语义：

- `SUMMARY` 负责事实提取、Grounding、Coverage 和可读 Summary 渲染。
- `CONCEPT` 负责从 grounded facts 提取 Concept/Entity Candidate、aliases、supporting fact IDs。
- `GRAPH` 与 `WIKI` 在 `CONCEPT` 成功后 fan-out，互不依赖。
- `RESOLUTION` 可在 Graph/Wiki 生成后异步收敛 alias/canonical entity，不阻塞基础可用性。

当前代码真实问题已确认：

- `DurableCompilationWorker.processSummary()` 当前只 enqueue GRAPH；
- `JdbcGenerationTaskRepository.completeGraphAndQueueWiki()` 只有所有 GRAPH 成功后才 enqueue WIKI；
- 因此当前真实实现是 `SUMMARY → GRAPH → WIKI`，与目标不符，必须修复。
- 当前代码中没有独立 `Concept` 编译层，需要新增，而不是让 Graph/Wiki 各自重新理解实体。

---

## 3. Prompt Template 设计

### 3.1 Summary Prompt

保留通用模板，同时允许按文档类型选择轻量 Prompt：

```text
summary/general
summary/article
summary/report
summary/contract
...
```

模板决定“如何总结/提取事实”，不定义强制企业字段。

输出继续以 `FactProposalSet` 为核心：

- section：内容驱动的自然栏目；
- fact_text：原子事实；
- evidence_block_ids：必须来自真实 Raw Block；
- 后续独立 Judge 做 Grounding；
- Coverage Judge 检查重要事实遗漏。

### 3.2 Concept Prompt

新增 `concept/extract` Prompt。

建议输出：

```text
ConceptCandidate
- candidate_id
- preferred_label
- aliases[]
- concept_type_hint
- description
- supporting_fact_ids[]
- confidence
```

其中 `concept_type_hint` 是轻量提示，不作为 Graph 最终 Entity Type 的唯一事实来源。

Concept 必须绑定 grounded fact；没有 supporting fact 的 Concept 不进入 ACTIVE。

### 3.3 Graph Prompt

保留严格 ontology：

- Entity Type vocabulary
- Relation Type vocabulary
- relation source/target type constraints
- supporting_fact_ids
- valid_from / valid_to
- confidence

LLM 输出视为 Candidate；最终合法性由确定性 Validator 决定。

### 3.4 Wiki Prompt

输入：

- Wiki Subject / Concept
- aliases
- grounded facts
- supporting summaries
- 可选已解析 Concept 关系

输出内容驱动 section/claim；不固定“基本信息/职责/风险”等章节。

每个 claim 必须引用 supporting_fact_ids，之后做批量 Wiki Judge。

---

## 4. Concept 数据模型

新增持久化事实：

```text
concept_candidate
- concept_id
- version_id
- preferred_label
- concept_type_hint
- description
- lifecycle
- model
- created_at

concept_alias
- concept_id
- alias

concept_fact_support
- concept_id
- fact_id
```

原则：

- Concept Candidate 是共享中间层，不等同于最终 canonical entity。
- Graph 与 Wiki 均引用 concept_id；
- Resolution 可以将多个 concept candidate 映射到同一 canonical entity。
- 不能仅凭字符串相似就合并；相似但不同的委员会必须能稳定分开。

---

## 5. Generation Task DAG 重构

### 5.1 Task Type

扩展：

```text
SUMMARY
CONCEPT
GRAPH
WIKI
RESOLUTION
```

### 5.2 幂等键

保持：

```text
generation:{task_type}:{version_id}:{subject_id}
```

但 `subject_id` 必须有明确语义：

- SUMMARY：空/版本级；
- CONCEPT：summary_id；
- GRAPH：summary_id 或 concept batch id；
- WIKI：wiki subject/concept id；
- RESOLUTION：version/corpus scope。

### 5.3 Fan-out

正确任务关系：

```text
SUMMARY complete
→ queue CONCEPT for each ACTIVE summary

all required CONCEPT for a version complete
→ queue GRAPH
→ queue WIKI
```

GRAPH/WIKI 各自完成，互不触发对方。

若暂时按每 summary 处理 Graph，则 Wiki 也必须在 Concept 后独立 queue，不能依赖 Graph complete。

### 5.4 局部重试

- GRAPH FAILED → 只 retry GRAPH；
- WIKI FAILED → 只 retry WIKI；
- CONCEPT FAILED → 不重新 MinerU/Embedding/Summary；
- app restart 将 RUNNING generation task 安全恢复为 PENDING；
- 不得形成重复下游任务。

---

## 6. Graph Validator 改造

当前 `GraphCompilationService.validateExtraction()` 对任意非法 relation 直接抛异常，导致整份任务失败。

改为：

```text
Candidate Relation
→ validate
  ├─ accepted
  └─ rejected(reason)
```

可单条拒绝：

- unsupported entity type
- unsupported relation type
- invalid source/target type
- missing support fact
- supporting fact not graph eligible
- evidence missing
- self-loop
- invalid confidence
- invalid temporal range

覆盖率规则仍严格：

- 每个输入 grounded fact 必须有 fact_decision；
- graph_eligible=true 的 fact 必须至少被一个 **accepted relation** 覆盖；
- 若 accepted relation 过滤后导致 eligible fact 无覆盖，则 Graph task FAIL；
- rejected candidates 记录到质量诊断，不直接污染 Neo4j。

---

## 7. Wiki 跨文档聚合

当前 `DurableCompilationWorker.processWiki()` 仅加载：

```text
loadActiveSummariesForVersion(task.versionId())
```

且公司有 metadata 时以公司名生成统一 wiki_id，因此不同文档可能不断对同一 company Wiki 产生“单文档版本覆盖”。

目标改为两层：

### 7.1 Wiki Contribution

每个 source/version 只产生 grounded contribution：

```text
claim
supporting_fact_ids
concept_ids
evidence_ids
source_version
lifecycle
```

### 7.2 Wiki Materialization

按 canonical subject/concept 聚合所有 ACTIVE contributions：

```text
Wiki Revision N = materialize(all ACTIVE contributions for subject)
```

要求：

- 新文档加入 → 新 revision 增量丰富；
- source version superseded → 失效 contribution 不再进入最新 materialization；
- 历史 revision 可读；
- 不能用单份新文档覆盖旧 Wiki 正确信息。

第一阶段可以先实现“按 subject 聚合所有 ACTIVE summaries/facts”作为最小可用版本，再演进为显式 contribution table。

---

## 8. Resolution / Reconciliation

Entity Resolution 不应只发生在 Graph 抽 relation 时。

目标：

1. Concept 产生 candidate + aliases；
2. Graph/Wiki 均引用 candidate；
3. Resolver 根据规范化名称、别名、类型、共享证据/上下文做 canonical mapping；
4. 对高风险相似实体 fail-closed，不自动合并；
5. Resolution 可后置重跑，并驱动 Graph/Wiki projection 更新。

必须长期回归：

```text
审计委员会
投资与决策委员会
```

二者名字相似但绝不能误合并。

---

## 9. 在线查询与后台编译资源隔离

当前真实验收已暴露：后台 Embedding 可长时间占满本地模型服务，导致 Ask 超时。

固定优先级：

```text
实时 Query Embedding / Rerank / Ask
>
普通 ingestion embedding
>
Summary/Concept/Graph/Wiki 后台编译
```

近期最小措施：

- ingestion embedding batch 默认验收值 16；
- timeout 后 batch 二分 fallback；
- 后台任务限并发；
- 不同时启动多份大型 PDF embedding/knowledge build；
- 在线 Ask 验收放在 ingestion 重负载清空后执行。

中期补独立 worker pool/并发配额。

---

## 10. 当前已完成且必须保留的修复

不得回退：

- Smart Chunk：去除页码/页眉/页脚独立 chunk，heading context 与正文组合参与检索；
- Evidence：原文展示正文，内部保留 heading context；
- PDF bbox：真实万科 PDF 可准确圈选原文；
- ZIP：扁平可读路径、UTF-8 文件名、provenance manifest；
- parser_task_id 持久化：MinerU restart/resume 不重复提交；
- FAILED ingestion 从已有 Raw Block 后半段恢复；
- structured output camelCase→snake_case 兼容；
- Summary/Wiki 内容驱动，无固定业务 section 模板；
- Summary multi-fact batch grounding；
- Wiki multi-claim batch grounding；
- Chat transient retry：429/502/503/504/IOException；
- 每次 transient retry 使用新 HTTP client/connection（待 infra 回归与部署）；
- Embedding batch 配置已在验收 profile 调到 16。

---

## 11. 立即执行顺序

### Gate 0：恢复现场并完成未部署修复

1. infrastructure Spotless；
2. `SpringAiAlibabaKnowledgeModelTest` + `MinerUPipelineAdapterTest`；
3. application compilation suite；
4. install/package 最新 jar；
5. 部署 `ekb-knowledge-server`；
6. health check。

### Gate 1：先清空当前 ingestion backlog

1. 查询 06 当前最终状态；
2. 若 06 ACTIVE，再 retry 01，确保使用 batch=16；
3. 不让 01/06 同时重压 embedding；
4. 两者完成后再进行 Ask/knowledge generation。

### Gate 2：修正知识编译 DAG

1. 新增 CONCEPT task/service/persistence；
2. 移除 `completeGraphAndQueueWiki` 语义；
3. Summary → Concept；
4. Concept completion → Graph + Wiki fan-out；
5. Graph/Wiki 独立状态与 retry；
6. 补 task DAG tests。

### Gate 3：真实 03 审计委员会知识构建

必须真实得到：

- Summary ACTIVE；
- Concept ACTIVE；
- Graph ACTIVE；
- Wiki ACTIVE；
- Graph relation → fact → evidence；
- Wiki claim → fact → evidence。

### Gate 4：真实 05 投资与决策委员会

目标：验证两个相似委员会不误合并。

联合问答必须分别得到：

- 审计委员会：3 名董事，其中 2 名独立董事；
- 投资与决策委员会：3 名董事，其中 1 名独立董事；
- 两组 citation 分别回到正确文件。

### Gate 5：跨文档 Wiki

引入 02/04，再选一份年报，验证同一主题 Wiki 新 revision 是聚合而非覆盖。

### Gate 6：扩大真实语料

建议顺序：

1. 14/15 可持续发展报告；
2. 07/08 业绩演示；
3. 09~13 年报。

始终控制 MinerU/Embedding 并发，不一次性把 15 份全塞满。

---

## 12. 最终回归矩阵

必须覆盖：

- Parser / MinerU resume
- Smart Chunk
- Evidence / PDF bbox
- Embedding / OpenSearch
- Rerank
- Summary
- Concept
- Graph
- Wiki
- Entity Resolution
- Search
- Ask
- adaptive multi-round
- WAITING_USER clarification
- Query History / Group / Package
- source ZIP
- Model Configuration
- Admin/User/RBAC
- Audit + CSV
- MCP search/package
- restart recovery
- failed task retry
- missing object recovery
- desktop UI
- 390px UI

工程 Gate：

- Maven targeted tests
- Maven full verify（必要时分模块，避免本机资源拥塞）
- Spotless
- Architecture tests
- frontend lint/typecheck/build
- Docker health
- Browser smoke
- release hygiene
- `show_changes`

---

## 13. 最终完成标准

只有以下同时满足才能更新 `.ai-bridge/agent-status.md` 为完成：

1. Raw 主链稳定；
2. Summary/Concept/Graph/Wiki DAG 正确；
3. Graph/Wiki 无硬前后依赖；
4. Graph/Wiki 均 evidence-first；
5. Wiki 跨文档聚合不覆盖；
6. 相似实体不误合并；
7. 至少一组真实 Graph/Wiki 浏览器效果可验；
8. 自适应 Ask 与 citation 通过；
9. MCP 真检索/打包通过；
10. Java/Frontend/Docker/Browser/Security 全量 gate 通过；
11. 当前未提交工作树不被 reset/stash/clean/restore；
12. 不 push，除非用户明确要求。
