# Raw 解析版本与检查点

对应 KB-01。解析内容能复用，必须证明保留的是同一原件与当前实际处理管线。正文 Hash、完整编码输入与 ServingIdentity、ACL 各有独立身份；解析身份不会代替模型契约或访问权限。

## 统一通路

1. 原 durable executor 校验完整原件 SHA 与字节数，读取实际 parser metadata。外部 HTTP / MinIO 读取不占据源 reservation 的 PostgreSQL 事务。
2. `ParserPipelineIdentity` 对实际声明做排序的 UTF-8 长度编码。MinerU 声明包含已冻结 app / modules、完整本地模型 manifest 摘要、原解析 profile / 依赖 / 文本表策略。Java 声明另包含路由、实际 adapter / Raw builder / evidence / artifact encoder 实现字节（含嵌套类）、tokenizer 与 chunk profile。没有未知身份 fallback。
3. `RawParseCheckpoint` 在原 saveParsed 短事务中绑定源 SHA / 字节数、pipeline、完整 RawBlock 集合和 Markdown / structured 物理对象 SHA。每个 ParsedBlock 的原 receipt 都要绑定同一完整源和 parser declaration。
4. FAILED / PENDING 的已解析恢复、外部 COMPLETED task 复用、ACTIVE 原件幂等复用共同使用这份证明。空、旧、缺失、损坏或部分结果都不能算当前认证。不可解码的保留 checkpoint 原行不修改，按无证明处理；未发布任务可在原次数内重解析，ACTIVE 不能静默复用。
5. 外部已完成结果的原件 / pipeline 不符时，保留 MinerU ledger，清除当前 ingestion_file 的无效外部 taskId；仅该窄类别进入原有 Raw 有界退避，不新增 HTTP 重试、不把源码 / 配置 / 权限错误变成可重试。
6. embedding 仍根据完整实际文本、frozen RawEncodingProfile 与 actual ServingIdentity 查原缓存。相同输入与实际模型身份的向量可复用。
7. 发布前再次读取实际当前 declaration；原短事务同时要求 owner / epoch / 固定阶段期限、源 pipeline / checkpoint、ACL 与较新 PENDING / ACTIVE processing version fence。空 legacy pipeline 不能走新发布。

声明证明当前配置及不可变发布 bundle；metadata GET 不执行推理，也不声称对每次请求重新散列所有大模型权重。原 native parse 仍执行其既有模型 manifest / 权重校验。

## V23 与显式重新解析

V23 保留全部旧版本、原对象、任务和尝试次数。旧 NULL pipeline 不回填。真实源的 legacy `(document_id,sha256)` 独立唯一；运输保留的固定 `UNVERIFIED` 使用独立版本 ID，不能当正文身份。新 `(document_id,sha256,parser_pipeline_sha256)` 唯一，版本号独立唯一。

资料详情提供“重新解析”，调用正常 API。2026-10-08 已执行并正常部署前端交互回归；真实原件和处理状态的业务验证单独进行：

```text
POST /api/enterprise/document/reparse?version_id=<实际可访问版本>
```

必须有当前上传权限，且为该资料 owner 或管理员，并满足资料及继承密级。先校验实际权限，SQL 短事务外读取 parser declaration，然后重新校验权限及原件，再保留真实新 processing version / batch / file / outbox。只在实际管线不同且不存在该来源 / 管线版本时创建新任务。相同身份返回原任务，FAILED 仍使用原任务的正常重试，原 FAILED5 不重置。

重新解析复用保留原对象；与普通新暂存任务的首次原件存储通过 `immutableOriginalReserved` 区分。旧 ACTIVE 在新 PENDING / FAILED 存在时继续可读，原子成功发布才切换版本和权限 / 索引 authority。共享 Raw failure 事务同时结束其未发布 PENDING 版本，覆盖尚未进入解析阶段的 metadata / 原件恢复错误；已经 ACTIVE 的重复任务目标绝不因此撤下。没有自动全库重建。

重复 multipart 完成仍保留它的上传会话、原对象与运输版本。完整 SHA 校验后，只可幂等指向相同来源 / 实际管线的 ACTIVE 版本；运输版本正常置 INACTIVE 并记录 duplicate lineage，其原件身份仍是未认证运输记录。不能借另一 FAILED / PENDING 任务创建新模型次数。

当前相同 pipeline 的 ACTIVE checkpoint 如果物理损坏，无法通过“相同身份幂等”获得新的免费尝试。缓存会明确拒绝认证；独立数据修复 / 恢复规则需另外实现和验证。本入口不宣称修复任意损坏。

## 本批证据与运行边界

- 新 metadata app / 原 Python 表格入口：33 / 33 组件通过，其中 27 为原 PDF / bundle 回归，6 为纯 metadata 协议断言。metadata 测试使用合成依赖声明，不等于运行时 ready 或真实推理。
- 固定 native 调用与观察：6 / 6 组件通过，原 profile / 600 秒 / 页数调用不变。
- JDK 17 聚焦 51 / 51 通过；完整 clean verify 361 项中 360 通过、1 项真实模型环境未启用而跳过，0 失败 / 错误，53 份 fresh XML。受测后端已按原部署守卫正常启动，实际 immutable Jar 为 c40d8ee831a30eed5960d0856ccad339fa94205038e38184ca1ea71ff0adac83。
- 原生 V23 升级 / 并发 / ACL / late fence、独立 Milvus namespace 的真实 dense topK / BM25 过滤：54 / 54 通过。包括原 10 秒退避后同任务真实第二次 claim、保留首次失败以及 FAILED5 不产生第六次；未调用模型。前两轮 native fixture 的 UUID / VARCHAR 约束错误保留为失败，部分断言不算完整通过。
- public 通过正常 Flyway 执行 V23，19 张核心表逐行摘要与部署前完全相同，56 个旧来源版本仍为未知 pipeline / checkpoint，没有回填认证。原真实 FAILED5 任务和历史保留。
- 新 metadata app 555ddf 已正常部署于 bundle 30033d，原 MPS / 4 CPU / 600 秒、模型及 manifest 不变。部署时实际 ready / pipeline canonical / 文件身份 / 24 行 parser ledger 保留等 12 项通过；无 worker、无新解析。此部署检查不继承旧 bundle 的内容通过；后续同原 PDF 的真实失败单独记录于下。
- 2026-10-08 重新解析 5 项及失败提示 7 项实际浏览器交互通过，lint、类型检查与生产构建通过；使用原原子发布入口部署 24 个构建文件。首次默认 Chromium 缺失为环境失败；使用已安装的 Chrome 后 9P/3F，因合成夹具的 `output` 也有隐式 status 导致定位歧义。实际状态提示补可访问名称并使用明确名称定位，保留所有业务断言后 12P/0F/0skip。这些接口响应是明确的合成夹具，不能算正常 Raw 上传或真实重解析通过。
- 同日按原部署守卫恢复 c40 Java 与 30033d parser：19 张核心表原字段、原 FAILED5 全行与 24 行 parser ledger 保持，闲置 actor=NULL，原 MPS / 4 CPU / 600 秒未变。恢复检查不执行模型；后续真实文本重解析单独记录于下。
- 同日用原有密级 30 所有者，通过正常页面重解析既有 168 字节合成 Markdown 原件：真实 QUEUED → RUNNING / EMBEDDING → SUCCEEDED，attempt=1 / epoch=1。观察到真实 Qwen 编码，PENDING 期间旧 ACTIVE 及 authority / 原文块保留，原子发布后旧 INACTIVE / 新 ACTIVE；未改数据库制造状态。新来源采用 `local-text / 2` 与当前 V23 pipeline / 完整 checkpoint。实际 PG / MinIO / Milvus / 原消息 Redis 读回 23 项通过；当前 identity 的正常重复页面操作及两并发 API 复用同任务，权限 / 幂等 / 原 FAILED5 保留共 7 项通过。读者页面不显示保密资料和写入口。这是文本显式重解析闭环，不是新 Raw 上传、PDF 或新知识生成闭环。
- 真实版本切换后发现旧详情刷新会请求已失效的版本并误报 403。刷新改为先读取当前列表，再按实际版本或同资料的新 ACTIVE 同步详情；来源退出当前可见列表时清除旧详情和待返回请求。两项新回归先真实 RED，再与原 12 项合并 14P / 0F / 0skip，并经正常 lint / 类型检查 / 构建入口发布。此夹具明确使用合成 API，不能代替后台发布验证。
- 随后统一列表与详情读取作用域：使用 Preact hook 保存请求 ticket，筛选变化、下一次请求、关闭 / 切换及卸载使旧 ticket 失效。刷新等待列表时保留当前选择 ticket，用户后续操作优先；旧请求的结果、错误和 loading 收尾都不得覆盖当前界面。后台刷新保留既有列表并标记 busy，初次空列表仍显示加载骨架。4 项延迟回归先 RED，再与原 14 项合计 18P / 0F / 0skip，沿用正常构建发布入口。 最终共享处理类型格式器由首页和任务页复用；首页标题为“最近处理记录”，逐行显示实际 task_type 对应的处理类型。真实首页首项为重新解析后的 SOURCE_ASSOCIATION，不把该关联识别完成算成新摘要、概念、图谱或百科生成通过。最终 51 份前端源码、24 份构建文件及实际 HTTP 入口已交叉绑定，真实页面读取当前 v2 正文、关闭详情和处理类型；延迟场景仍为明确的合成组件夹具。
- 同日新 30033d 对同一 155311 字节、SHA aba0b5bb… 的两页原件实际解析在原 600 秒期限自然失败，`PARSER_STAGE_TIMEOUT`；外围观察 601.562 秒。15 个内容检查与 11 个视图检查均未到达，不记成通过。原生 Layout 和表方向识别完成，采样停留在实际 Qwen2VL forward / generate；没有足够证据判定性能根因。完整 stack / stage / 原始日志、原件、实际命令与旧 153.181 秒服务用时对照保留。自然释放与数据保留 7 项通过：原 24 行未变，仅增加本次 FAILED，owned 进程组无 live、worker=NULL、服务仍 ready。这 7 项只证明失败后的资源恢复。
- 保留旧 Java d01 的 338P 与旧 parser ad469 的真实两页 15P，均只属于对应原冻结发布。新 Java 360P 不包含真实模型测试，新 parser 的 ready 通过也不等于解析通过。
- 原实际模型失败五次及 Luna diagnostic timeout 保留；新 Raw 上传 / 新 Summary / Concept / Graph / Wiki 闭环仍未通过。

## 手动查看与独立读回

正常资料库的“重新解析”入口保留原件，用户不需要填写管线参数。后台记录可在“上传与处理”查看。检查旧 ACTIVE 在新版本处理期间仍存在，并在成功后才切换；重复当前版本的操作应复用同一任务、版本和尝试次数。

通用只读 SQL 需要传入实际资料 ID：

```sh
docker exec -i evokb-target-postgres-1 psql -U evokb_target -d evokb_target \
  -At -v ON_ERROR_STOP=1 -v document_id='<实际资料ID>' -f - \
  < scripts/sql/raw-checkpoint-readback.sql
```

该 SQL 只展示 PG 的原文版本、checkpoint、RawBlock、文件、publication / member / authority 和 outbox，不能凭字段存在就认证物理对象或向量。结合已冻结原件，用以下检查器读取真正的 PG / MinIO / Milvus / Redis 与实际 resident 编码声明：

```sh
python3 scripts/test-raw-publication-readback.py \
  --logical-name '<实际资料名称>' --version-id '<实际新版本ID>' --file-id '<原正常文件ID>' \
  --original '<未改动原件>' --parser-name local-text --parser-version 2 \
  --require-current-checkpoint --output '<全新私有报告路径>'
```

默认期望仍为 `mineru-hybrid-engine / 3.4.5`，文本检查必须明确传入 `local-text / 2`，不能从被测 DB 反推期望值。`--require-current-checkpoint` 使用部署 Jar 中真实 parser / router / tokenizer / chunk / encoder 字节独立生成当前声明，并验证原件、RawBlock、Markdown / structured 物理 SHA；不能对旧 NULL 行回填认证。`--parser-ready-url` 仅用于实际外部 MinerU 输入，不附加到本地文本证明。

2026-10-08 的读回器修复保留真实失败：局部变量同名导致首次 javac 失败；旧只读配置的空 rerank URL 被生产校验器拒绝；随后 plain ObjectMapper 无法解码生产 `SNAKE_CASE` 的完整 checkpoint，返回未认证。检查器现读取真实完整配置，并沿用生产命名契约。PG 当时已经保留完整 checkpoint，structured 实际 SHA 与保存值相同；没有修改业务配置、产物或源状态以让检查通过。最终实际重新编译并执行 23 项 GREEN，所有旧 FAIL 报告和冻结源码保留。

原生组件可重跑入口（只使用独立保留 schema，不改业务状态）：

```text
scripts/test-postgres-parser-checkpoints.py --jar-path <受测新Jar> --pre-upgrade-jar <保留pre-V23Jar> --output <全新报告>
```

`--source-migrations --classes-path` 仅用于明确标记的 candidate 组件 overlay，不能算部署。此原生组件在独立 PostgreSQL schema / Milvus database 里使用明确合成的 1024 维向量，不调用模型。它检查较强的未发布新向量不会挤掉旧 topK、新失败仍可查旧版本以及真实成功才切换；这仍不等于真实模型上传闭环。脚本保留实际旧 Jar 迁移字节、旧 schema 的原 fixture、升级 SQL、源 / class / Jar 哈希及自然测试结果。真实业务验收还需要正常 UI、任务记录、PG / MinIO / Milvus / Redis 与独立 Gold 交叉读回。
