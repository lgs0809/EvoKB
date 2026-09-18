# 暂存、发布指针和事实引用（KB-01/02/03 子批次）

本批把外部生成与数据库发布分开，沿用 Spring TransactionTemplate、现有任务账本和 Milvus。不会把模型调用、MinIO 上传或向量编码放进最终发布事务。它不是 EvoKB 整体验收完成证明。

## 发布合同

任务开始时记录本轮全部实际来源版本与 ACL，包括最终没有引用的来源。Summary、Concept、Wiki 和 Graph 通过同一个 CompilationPublication 执行短发布事务，校验 owner、epoch、数据库 600 秒期限、来源有效性和全部输入 ACL。输入在同一执行中不能重新绑定。模型、对象存储和外部索引先准备好，再提交业务记录及当前发布指针。

MinIO 对象使用 `compilation/<task>/epoch-<epoch>/…` 路径。Milvus 使用不可变 `asset_id:r<revision>` 物理行；数据库的 `kb_asset_current` 决定当前可读修订。旧模型或旧执行者后来覆盖旧行，不能替换当前修订。未发布的暂存行不能检索、展开或取得原文件归属；历史已发布修订继续保留，下载仍检查当前来源权限。历史无修订的 Milvus 行在迁移前可读，有新当前指针后停止服务；同一逻辑对象只返回一次。

V12 是新增表/列迁移，不删除历史数据。升级前后的原 67 张表中，66 张既有列摘要一致；唯一变化是登录会话表的正常认证活动，前后都是 122 条。原始全表比较的失败报告保留，不能把它说成 67 张表全未改变。

## 已执行的证据

历史部署 `ac09942a…` 全量 Java verify 为 188 项。事实引用修复后的 `f5dc573a…` 为 196 项；本批运行中的 `2190e4bd…` 为 214 项：213 通过、1 个旧真实模型 smoke 未启用而跳过。新构建日志与 38 个实际运行测试类 XML 一致，编译前后源码哈希一致。上述 skip 不计通过。

在 `2190e4bd…` 上，真实 PG/Milvus 暂存组件 15 项、同库编译控制 12 项、八个知识库并发 6 项和 Wiki 关键词原子发布 5 项通过。Wiki 关键词写入与百科、当前索引指针共用一个短发布事务；回调在已撤权或已完成任务下不可执行。PG 实测同时发现旧 LIKE ESCAPE 非单字符错误，修复前两轮失败保留。组件向量和 Wiki 陈述是明确标记的夹具，不能替代真实模型质量验收。

V14 用知识库控制行、owner、task epoch 和数据库 600 秒 deadline 串行领取编译阶段；不同知识库可以各领取一个阶段。没有心跳续约。之前实际等待 600 秒和正常退避的 17 项检查在构建 `46d3d451…` 上通过；当前部署构建 `2190e4bd…` 的独立真实 600 秒到期复测也已完成，17 项检查通过，结果为 `kb-control-current-real-expiry-1.json`。没有直接改库缩短期限。该并发结果只证明控制记录隔离，不等于所有历史存储已实现多知识库隔离。

通过真实页面对虚构 `HT-MV-017补充协议` 提交生成后，摘要首轮编码依赖失败并按正常 10 秒退避；第二次 Terra 生成与实际 Qwen 编码完成，摘要正常发布在 epoch-2 对象路径。概念阶段随后两次因模型拼接不存在的 UUID 而失败，失败记录保留，未给它伪造成功状态。

针对引用传递，Concept、Graph、Wiki 共用请求内短引用键，完整事实文本不丢弃；回复中的键精确映射回原始事实 ID。原始 ID、证据和持久化身份不变。未知或拼错的引用继续拒绝，不按相似度修补 UUID。正常页面重试后，补充协议 Concept 第三次、独立 Graph 和 Wiki 在 `f5dc573a…` 上实际成功；Summary 的实际成功执行属于此前 `ac09942a…`。真实 Qwen 1024 维向量、8 个派生投影、Summary/Wiki MinIO 正文及 Wiki 12 条陈述的独立只读核对为 13 项通过。图谱 API 与原生授权 JDBC 两跳读取逐项相同，15 项通过；原探针错误地只允许本次来源、以及 Java 代理解析错误的两次失败均保留。

页面还实际验证了跨页退出后旧页收到 401、重新登录回到原百科，以及百科首次加载、选择、搜索和全局刷新保留条目/条件。截图和前端构建身份单列。新后端健康正常不代替这些真实模型和浏览器证据。

证据位于当前 Codex 工作目录 `work/resume-20261003/`：`derived-publication-*`、`staged-publication-*`、`fact-references-*`。报告记录实际 JAR/源码/日志哈希、保留的独立 schema 及种子 SQL。

## 重跑与手动查看

从仓库根目录执行，每次换新结果路径，保留失败。组件仅增加保留的隔离 schema/集合，不清理业务数据。

```sh
python3 scripts/test-derived-publication.py --mode after --output .tmp/target/publication-manual-01.json
python3 scripts/test-postgres-graph.py --output .tmp/target/graph-manual-01.json
python3 scripts/test-postgres-generation-stage.py --output .tmp/target/stage-manual-01.json
python3 scripts/test-postgres-kb-compilation.py --output .tmp/target/kb-control-manual-01.json
python3 scripts/test-postgres-kb-compilation.py --wait-deadline --output .tmp/target/kb-expiry-manual-01.json
python3 scripts/test-wiki-database-projection.py --output .tmp/target/wiki-commit-manual-01.json
docker exec -i evokb-target-postgres-1 psql -X -v ON_ERROR_STOP=1 -U evokb_target -d evokb_target < deploy/acceptance/staged-publication-inspection.sql
```

页面是 `http://127.0.0.1:18481/enterprise/tasks`；登录资料仅保存在 Git 忽略的私有配置中。失败任务的“重试此阶段”走正常任务流程。SQL 默认查询上述补充协议，也可以给 psql 传 `-v version_id=<实际版本>`。全量构建/部署用 `scripts/record-backend-verification.py` 和 `scripts/deploy-target.py`，替换运行进程前应确认没有正在执行的任务。

## 待完成

稳定条目跨来源晋升、实际下载驱动的夜间升阶、完整问答/撤权/恢复与正式 240 题评测仍需独立完成。后文记录已新增的实际 PDF、multipart 和持久加工入口证据，不能继续把这些整个功能称为未开发，也不能据此宣布 KB-01～KB-04 整组结项。

## 稳定条目和选择性加工整合批次（2026-10-03）

V15 的稳定条目目录分别保留实际收录来源与生成时完整输入依赖。跨来源归并须有相同且原文支持的业务范围、类型和精确候选身份；同名不同合同不会自动成为候选。Concept 晋升 Wiki 保留同一个资产 ID，权限变化立即让包含旧输入的派生读取失效。V17 的加工候选只从实际完成下载的原有账本选取；冷资料只进入独立后台关联识别，不自动生成摘要。

合并后的首轮完整 Java verify 为 228 项，227 通过，1 个真实模型 smoke 未启用而跳过，构建 `4c0d4e39…`。后台关联识别 8 项单位测试和 Raw 重试分类 6 项均实际执行。这个构建**没有部署**：之后的实际并发测试发现刷新候选与选择不在同一事务，两个线程可使选择跳过全部锁定候选，却提交当天已完成。原失败 `selective-generation-native-1.json` 和 schema 保留。

修复后用冻结源码类加首轮构建依赖执行实际 PostgreSQL 复测，`selective-generation-native-2.json` 的 15 项检查通过：并发选择共提交 40 个新任务，Summary/Wiki/Graph 分别为 20/10/10；剩余候选留在队列，重复调用与新实例不重复支出配额；来源热度不能冒充条目自身 5 次贡献；修复任务优先级 100，已满新任务配额不阻止修复。夹具的查询、陈述和下载 payload 是合成组件输入，实际生产完成回调与去重事务被执行，**不算真实页面下载或午夜实等验收**。

另有稳定条目真实 PG 组件 19 项通过（`stable-entry-component-4.json`，保留 schema `evokb_entry_component_0b440e69d5f3`），此前三次夹具/查询失败全部保留。它验证归并身份、晋升指针、旧输入权限失效、候选文本清理与原生下载贡献；不含实际模型生成。

重跑时保留每次新输出路径；默认使用当前构建的 JAR，必要时用 `--jar-path` 指定不可变已验证产物：

```sh
python3 scripts/test-postgres-knowledge-entry.py --output .tmp/target/entry-component-manual-01.json
python3 scripts/test-postgres-selective-generation.py --output .tmp/target/selection-component-manual-01.json
```

脚本在同一目标 PostgreSQL 内创建独立、保留的 schema，每轮 `.seed.sql` 含全部迁移和组件输入。使用 `SET search_path` 只读查看对应 schema，不覆盖 `public` 业务数据。新完整构建、普通部署与真实模型/浏览器验收通过前，不把该子批次标记为整体完成。

## 已部署与真实页面证据补充（2026-10-03，第三次部署）

第三次管理部署的历史后端为 `28ff064e13d109d39d4707d78233d41c3f645de3bc52fce569856a8a61f02702`。`entry-raw-backend-verify-6.json` 对应 41 个本轮真实 XML、235 项测试：234 通过、1 个未启用的真实模型 smoke 跳过，0 失败/错误；编译前后源码一致。`entry-raw-deploy-3.json` 只证明该产物正常启动，功能证据另列。此前 full3 的事实范围诊断兼容失败、full5 的测试通过但源码在编译期间变化均保留；full5 不可部署，也没有部署。

`verify-backend.py` 统一保存格式检查、编译前后源码快照、完整 Maven 日志、XML 汇总和退出状态。`deploy-target.py` 拒绝源码一致性未知或不一致的产物，避免共享工作区同时修改时发布混合构建。每次结果文件应使用新路径。

```sh
python3 scripts/verify-backend.py --output .tmp/target/manual-verify-01.json
# 先确认该报告通过、sourcesStableDuringVerification=true，且没有正在执行的业务任务。
python3 scripts/deploy-target.py --help
```

### 原件与 Raw

真实页面上传的虚构采购 PDF（版本 `86d98d30-8a58-4a8e-83ec-90585f3be7ac`）通过后端官方 MinerU hybrid 路径完成解析与实际 Qwen 编码。原文、付款前提、单位与表格数据独立核对；内容页表格使用同一安全 Markdown 组件，保留合法跨行/跨列，不展示 HTML 源码。真实 PDF 与含攻击内容的渲染夹具分别保存，夹具不能充当业务解析证明。

真实页面上传的 64 MiB DOCX（版本 `99ceed60-9987-4a87-9596-90b9b4b970a3`）使用 5 个分片。客户端/服务端 whole SHA 一致，MinIO 完整对象已校验。首轮 Raw 完成阶段的 SQL 歧义失败保留；新产物部署后，正常页面重新处理同一文件、epoch-2 正常完成，不创建新来源、不改库造成功。已有 epoch-1 的有效 Raw 发布可以被完成恢复引用，验证发布范围与当前输入，不能把它写成重新发布 epoch-2。

原件实际下载、ZIP 完整性、正文付款条件和表格合并单元格分别核对。64 MiB 夹具包含不参与业务解析的填充 part，只证明大文件传输，不代表解析了 64 MiB 业务正文。multipart 当前普通 HTTP 验证 22 项通过；另有原生 600 秒签名到期后续传证据，不能把未重新等待的新版签名测试说成又等待一次。

V18 保留上传会话、分片与完整 SHA 状态；V19 保留加工命令及全部输入快照。两者均为新增迁移。MinIO CORS 仅允许本机界面地址；调整前后 83 个既有对象完整摘要一致，无数据卷删除。一次前端构建误覆盖 hash chunk 引发懒加载失败已保留并恢复；后续统一独立 outDir 构建、保存旧 hash 资产及原子切换入口。

### 稳定条目与持久加工

真实页面生成 `HT-ENTRY-104采购合同.md`：Summary 首轮正常成功；Concept 保留范围锚点拒绝及实际编码暂不可用记录，第四次真实执行成功，形成 4 个独立稳定条目（合同、两个主体及来源限定的产品）。该真实生成属于此前 `4b3f…`，不能写成 `28ff…` 重新生成。第三次部署上的只读 PG/Milvus/MinIO 核对 9 项通过：完整输入权限、已核验事实、唯一当前投影、实际非零 1024 维向量、原件及 Summary 完整摘要。见 `stable-entry-actual-publication-readback-1.json`。

普通 ADMIN 加工接口现在返回 `202 Accepted` 与持久 `taskId`。Summary、Graph、Concept projection、Wiki、Wiki keywords 的原有请求字段全部保存至 V19，不把模型调用放入 HTTP 事务。已有 Concept projection 重建只重建已发布条目投影，不重复提取。写入前检查提交者当前身份及全部输入权限；未保存命令的旧任务不猜测请求参数。普通 HTTP 实测 24 次相同 Wiki 请求收敛到一个 task、正常 worker 执行一次，权限与错误来源拒绝、重复成功请求不重新执行，共 11 项通过。该接口使用现有 `snake_case` 字段；一次 camelCase 测试脚本错误 400 保留。

稳定条目只读查询和冷资料后续关联属于下一批源码，第三次部署尚未包含：条目读端原生 PG 17 项通过；冷资料先完成空关联、之后出现对应条目再扫描的源码叠加组件 13 项通过，每轮最多 100 个来源、模型仍只由独立持久任务执行。无关来源、撤权、重启与重复扫描均单列。已发现的 pending 关联不重复排模型任务，冷资料没有生成伪热度。其两次错误的组件断言把独立新条目和合法新 ACL 工作误当重复，失败原报告保留，修正后不宣称生产修复了这两个测试假设。

```sh
python3 scripts/test-postgres-compilation-command.py --output .tmp/target/commands-native-01.json
python3 scripts/test-target-compilation-commands.py --output .tmp/target/commands-http-01.json
python3 scripts/test-postgres-late-source-association.py --output .tmp/target/late-source-native-01.json
# 查看真实采购合同的任务、条目、全部输入、待关联来源和真实使用贡献：
docker exec -i evokb-target-postgres-1 psql -X -v ON_ERROR_STOP=1 -U evokb_target -d evokb_target < deploy/acceptance/stable-entry-inspection.sql
```

实际访问地址为 `http://127.0.0.1:18481/enterprise/`。所有证据在本任务工作目录 `work/resume-20261003/`，初始化/探针 SQL 与失败尝试均保留。通过、失败、跳过、未测分别统计；正式 240 题、真实稳定条目跨来源 Wiki 升阶与实际午夜等待仍未通过，历史存储的完整多知识库隔离也不因任务控制并发检查通过而成立。

## 第四次部署：条目读端与旧资料关联恢复

第四次正常部署为 `560e067ef764556671c23e2259632f640ecc5521946e0cd28fc76f3e69beaab7`，启动于 `2026-10-03T05:17:13Z`。`entry-raw-backend-verify-7.json` 为 237 项：236 通过、1 个未启用的真实模型 smoke 跳过，0 失败/错误；源码清单前后一致。替换前实际入库和加工活动任务均为 0，原进程与不可变产物保留。`entry-raw-deploy-4.json` 不替代功能验收。

稳定条目列表与详情使用当前修订及完整输入权限校验，在筛选、分页和计数前排除失效来源、未经核验事实、待关联和 STALE 条目。当前普通 HTTP 检查 `entry-browse-actual-http-1.json` 的 12 项通过；实际浏览器已打开产品条目、来源、证据与原件。匿名 401、未知或不可读条目 404，不能通过客户端传知识库 ID 扩大权限。

旧资料先完成空关联、后来出现相应条目时，独立扫描根据完整条目身份和来源权限指纹重新排持久任务，每轮最多检查 100 个来源。当前不可变产物上的原生 PostgreSQL 检查 `late-source-association-current-artifact-1.json` 为 16 项通过，包括两个独立实例并发去重、撤权、重启、无关来源及已完成关联在新修订下重新打开。组件模型决定仍是明确标记的夹具，不算真实模型判断。

实际浏览器上传 `HT-ENTRY-104补充协议.md`（版本 `4b2b715d-196a-492f-972a-3994963d26e6`）后，Raw 正常成功，第一次关联因纯标题没有独立 Evidence 而失败。`RawEvidencePolicy` 统一复用原入库判定；纯标题可跳过，缺少证据的业务正文继续拒绝。新部署自动重新识别该旧来源，真实 Luna 关联于 `05:18:16Z` 首轮成功，保留合同和双方主体的实际引用，并将三个受影响条目标为 STALE；未修改的产品条目保持 ACTIVE。随后实际 Summary 首轮成功，Concept 尚在执行。旧失败没有删除，也没有直接改库成功。关联成功不等于新条目或 Wiki 已经发布。

新前端原子发布后，真实 PDF 的证据页 `raw-pdf-evidence-tab-browser-1.json` 有 6 项通过：完整两行合同、金额单位、质保与天数、付款前提均保留，表格不嵌进按钮且不展示 HTML 字符串。旧已打开页面的旧组件记录和刷新后的新截图分别保留，不能把缓存旧页面当成新代码执行。

只读观察脚本会保存原生任务、条目、完整依赖、待关联与发布指针；状态是观察结果，未终态时不生成验收 PASS。每轮必须换新文件：

```sh
python3 scripts/observe-target-stable-entry.py --version 4b2b715d-196a-492f-972a-3994963d26e6 --output .tmp/target/annex-observation-01.json
```

实际跨来源更新后的相同条目 ID、真实 Wiki 升阶、午夜任务与正式 240 题仍待独立结果，不能用上述 Raw、关联或浏览证据替代。

### 第四次部署后的实际跨来源更新与下载

以上“Concept 尚在执行”为当时的观察快照。该任务 `6d3ed4ba-7a01-4b0d-9b73-353cb1f5e08b` 随后于 `2026-10-03T05:24:57Z` 正常首轮成功。合同与双方主体保留原条目 ID，修订升为 2，重新变为 ACTIVE；另有两条范围独立且原文支持的新条目。`entry-annex-actual-publication-readback-1.json` 的 9 项原生 PG、MinIO、Milvus 检查通过，核验两个实际来源、完整输入 ACL、事实与引用、唯一当前投影、实际非零 1024 维向量和原件完整 SHA。随后自动 Graph 修复仍发生实际模型超时；不能把 Concept 更新算成 Graph 通过。

实际浏览器使用自然语言询问原合同与补充协议后的尾款期限、验收/发票条件、金额及质保。会话 `3f043636-3312-4ac3-87ef-9d9f045f9473` 正常 ANSWERED，回答保留 30 日改 15 日、验收及发票前提、900 万金额、24 月质保，并明确资料不足以计算具体付款日期。这是开发夹具上的一次真实使用，不能替代正式 240 题评测。

同页面正常下载引用原件 ZIP。`entry-annex-source-package-readback-1.json` 有 16 项通过：两个原件与夹具及数据库 SHA 完全一致；实际 ZIP 为 2182 字节，与完整下载回执一致；9 条实际展开记录只向两个参与条目、两个实际文档计入各一次贡献，其它目录条目没有广播热度。未写库制造下载或热度，一次贡献没有达到 Wiki 的 5 次独立使用门槛。

该次引用显示把 opaque object key 的 `original` 误当原件名称，页面内联预览因此不识别格式。原失败已保存；全量验证 8 的候选产物为 `fd7d980268813bae1336365bca6c4a68ef644597861bdf29d15d83b8328e100f`，238 项中 237 通过、1 个真实模型 smoke 跳过、0 失败/错误，源码前后一致。它从当前授权来源元数据读取原文件名，保留对象键、内容类型和原件字节。此处尚未确认正常部署与页面复测通过，不把候选构建算成运行成功。

下载证据复核脚本不调用模型、不产生新下载或使用贡献，每次用新的结果路径：

```sh
python3 scripts/test-target-source-package-readback.py \
  --session 3f043636-3312-4ac3-87ef-9d9f045f9473 \
  --download-path '/实际保存路径/knowledge-sources.zip' \
  --fixture-root deploy/acceptance/fixtures \
  --execution-proof '/原执行时保存的/entry-annex-native-publication-observation-11.json' \
  --output .tmp/target/source-package-manual-01.json
```

脚本明确区分产生问答的执行产物和后来只读检查的运行产物，不将重启后的读取冒充重新生成。正式评测、真实 Wiki 升阶、Graph 完成及午夜选择仍分别验收。
