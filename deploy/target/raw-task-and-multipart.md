# 原件任务、恢复与分片上传

本机隔离入口为 `http://127.0.0.1:18481`；PostgreSQL `15465`、Milvus `19535`、Redis `16385`、MinIO `19400`、MinerU `18486` 均保持本机访问。账号、密码、模型密钥只读自已有 `.tmp/target/accounts.json` / `.env.target`，回执不输出凭据或可绕过身份的签名 URL。

## 正常执行与发布边界

普通原件从上传会话完成创建一份预留版本、一个入库文件任务和 outbox，随后由后台抢占执行。`COMPLETED` 仅代表原件已合并保存，页面“排队中”不代表知识已经可用。原件校验、解析、完整文本编码、物理索引写入和短数据库发布事务逐项执行；只有当前权限/版本与 owner/epoch/600 秒期限都匹配时才切换当前索引指针。

Redis Stream 是投递机制，PG 是执行权。单次真实 claim 消耗一次尝试并增加 epoch，最多 5 次，失败等待 10/30/120/300 秒。坏输入及明确配置错误隔离，不无限重试。每个阶段用单个 DB 时钟设置开始和固定 600 秒期限；同阶段更新、扫描、PEL idle 和 Redis 短锁都不能续期。恢复扫描每 15 秒以 100 条游标读取；不在启动时把所有 RUNNING 改为成功或 PENDING。

原生 Spring Data Redis/Lettuce 消费者组、XPENDING/XCLAIM/XACK 保留投递身份。只有数据库允许恢复后才能接管；消息被 trim 时先保留原 stream/message/file 关联及重新投递事实，再确认 tombstone。Redisson 固定 15 秒调度锁仅减少扫描争用，不启用 watchdog，不授予业务执行权。

Milvus 新写入带不可变 `raw_scope` 物理身份。PG 指针与成员集合发布前，暂存及晚到旧 epoch 均不能成为当前检索结果；legacy 分支只允许没有 `raw_scope` 的旧行。全文本 embedding 不按字符截断/拆段/加权平均。缓存键包含完整编码身份和完整输入；缓存命中仍须执行当前 ACL 与发布检查。

普通上传完成后只登记来源关联/失效修复，不无条件把每份 Raw 全量升级为 Summary。选择性使用贡献和稳定条目演化有独立执行与验收证据。

## 分片策略与可手动操作的数据

`GET /api/enterprise/upload-sessions/policy` 返回实际统一限制：当前原件最大 256 MiB、16 MiB 分片、最多 4 个并行 PUT、签名有效 600 秒、会话绝对有效 24 小时。知识库来自运行时 `EKB_KNOWLEDGE_BASE_ID`（本机为 enterprise），不信任客户端自报知识库，也不宣称完整多租户隔离。

浏览器后台 Worker 流式计算全文件 SHA256 和各片 MD5。重新选择同名文件还必须匹配完整 SHA、大小及元数据，服务端实际 ListParts 决定哪些片可以跳过；不能以同名、同大小或前几个片数当完整身份。过期签名重新认证签发，不能通过续签延长 24 小时会话。完成请求以 revision/owner 固定清单，重复和并发完成只产生一份版本/任务。小时清理只取消本服务记录的过期未完成上传，保留已有原件和业务数据。

无客户端 SHA 的接口会话显式 UNVERIFIED；后端从对象存储流式校验完整原件，在短 claim 事务中确认 server SHA，才能发布。UNVERIFIED 不作为已验证哈希入库。已提供 SHA 却不一致时记录 INPUT_ERROR 与 actual SHA，原任务直接重试不能修好不一致的输入。

生成可反复使用的合成原件（已有输出必须保持原字节，换输出目录生成新组）：

```sh
python3 scripts/make-multipart-browser-fixture.py --help
python3 scripts/prepare-raw-cache-fixtures.py --output .tmp/manual-raw-originals
```

实际已保留的浏览器 fixture 为 `原件四分片传输验收-7d430c01fb.docx`：67,146,714 bytes，SHA256 `fd4ab5eb177d7db91f7acecaac8bb813b5a0a81a0279ae6deb544b761670e909`。业务正文、表格和图片未膨胀；额外 64 MiB 是合法但未引用的 ZIP 传输测试 part，明确只为五分片/完整字节测试，不冒充大型业务内容。

在页面“资料管理 → 添加资料 → 上传资料”选择原件，设置部门/类型/分类/标签，完成后从入库记录观察实际进度。资料内容页可以核对合同 HT-900、900 万元、质保 24 个月、发票且验收后 60 日、HT-901 暂缓付款、HT-902 的 120.50；表格保留 rowspan=2/colspan=2。证据页打开原文；DOCX 暂不在线渲染，可下载原文件并核验 SHA/ZIP，PDF 可在页面定位实际原件证据区域。

## 可重跑命令与保留的 SQL

每次使用新的结果路径，保留旧失败。先从 `.tmp/target/process.json` 取得当前 immutable artifact/SHA，不能把可变 target JAR 当已部署版本。

```sh
# 正常身份/API/真实 MinIO PUT；故意的坏 SHA 应由实际 worker 隔离，无模型调用。
python3 scripts/test-target-multipart-api.py \
  --jar-sha256 <process.json中的jarSha256> --output .tmp/manual-multipart-api.json

# 隔离 PG/MinIO 原生组件：含 24 个并发完成、真实四并行16MiB、重启合并恢复。
# 默认真正等到 600 秒签名过期；--skip-signature-expiry 只略过这一长等待，结果明确区分。
python3 scripts/test-native-multipart-upload.py \
  --jar-path <process.json中的artifact> --output .tmp/manual-multipart-native.json

# 冻结已部署 JAR：105条实际PEL跨页、两次独立进程崩溃、真正600秒阶段到期。
python3 scripts/test-raw-stream-matrix.py \
  --jar-path <process.json中的artifact> --output .tmp/manual-raw-stream-matrix.json

# 真实TCP中途断开和合并进程崩溃；真正等600秒后由HEAD恢复，只验证传输/任务创建。
python3 scripts/test-multipart-actual-crash.py \
  --jar-path <process.json中的artifact> --output .tmp/manual-multipart-crash.json

# 先准备两个真实16MiB分片，后续在普通页面同SHA/元数据重选并完成；不会替你complete。
python3 scripts/prepare-target-multipart-resume.py \
  --original <上面的67MB合成原件路径> --logical-name <新的合成知识名称> \
  --output .tmp/manual-resume-preparation.json

# 只读交叉核验实际正常上传产生的原件、任务、完整cache键及当前Milvus成员。
python3 scripts/test-raw-publication-readback.py \
  --logical-name 原件四分片传输验收-7d430c01fb --parser-name mineru-office \
  --output .tmp/manual-original-readback.json

# 正常业务schema只读核对既有任务：outbox/history、Redis消息原文与精确PEL、当前发布身份。
python3 scripts/test-target-raw-stream-readback.py \
  --file-id <既有正常上传的file-id> \
  --jar-sha256 <process.json中的jarSha256> --output .tmp/manual-stream-readback.json
```

SQL `deploy/target/raw-encoding-readback.sql` 可只读核对；正常 API 探针还保存本次实际 session/file/version 的 `.readback.sql`。原生组件保存独立 schema 的 `.seed.sql`、冻结源码、依赖 JAR SHA、真实迁移 SQL SHA 和日志；隔离组件的状态故障 fixture 不写普通业务 schema，也不制造真实模型完成状态。

## 已发生的实际结果

证据目录：`work/resume-20261003`（当前 Codex 工作目录下）。各报告带构建、原件、时间及组件边界，下面不能合成“一份新 JAR 全部重跑通过”。

| 范围 | 实际结果与绑定 |
|---|---|
| PG Raw claim/600/epoch/重试/outbox/ACL/来源并发预留 | `raw-task-native-5.json` 21/21；冻结源码组件，dependency `2190e4…` |
| 实际 Redis/PEL/trim/poison/固定短锁 | `raw-stream-native-1.json` 9/9，真实本机 Redis，独立 stream/group |
| 当前fd7真实105条PEL跨页、独立进程崩溃与固定600秒恢复 | `raw-stream-matrix-native-2.json` 14/14；迁移和类均取已部署immutable fd7。105条真实PEL中，首100个未到期DB owner不能抢，游标能恢复尾5个；独立JVM分别在终态提交前/提交后未ACK退出71。06:16:51.627303Z→06:26:51.627303Z真正600秒，不续期/不改时钟，过期旧owner不能写，新owner一次进入epoch2，最终PEL为0且消息原文保留。终态fixture为明确INPUT_ERROR组件事实，不冒充模型解析成功；首轮代理配置FAIL保留 |
| 冻结30个独立Stream案例，20开发/10留出 | `raw-stream-30-resumed-native-1.json`实际30/30案例PASS、246条断言；原执行类/迁移绑定3e981429，独立原schema `raw_stream30_97d652637486`、30份原件和stream。10个单独JVM真实退出71，隔离RedisTCP故障、11个原owner固定600秒不续期后正常attempt2/epoch2发布/ACK，25份有效发布，其余5例为非法交付/毒任务隔离。PG/MinIO字节SHA/Milvus当前成员与更优晚到旧向量/精确PEL全部交叉核验。向量明确合成，不是模型或效率成绩；原事故失败及后续恢复分别保留 |
| 普通CUA PDF与五片续传任务的main-stream只读交叉核对 | `raw-normal-stream-readback-2.json` 11/11；当前observer3e981429读取两份既有正常任务（实际执行仍a2d6/28ff），SUCCEEDED终态、outbox与精确delivery history一致，原stream/file消息保留、该message ID已无PEL、PG当前publication仍指向其版本。未进行新claim/ACK/模型/重启；首轮fd7绑定guard拒绝保留为NOT_RUN |
| 真正等待 DB 600 秒后拒绝旧 owner、恢复新 epoch | `raw-actual600-native-1.json` 5/5；没有改 DB 时钟冒充长等待 |
| 真正底层 Qwen 的完整输入缓存计数 | `raw-real-counter-4.json` 6/6，2 次实际成功 HTTP，重复输入 0 额外调用；之前 codec/代理失败保留 |
| 实际 Milvus 暂存/晚到/topK/legacy/ACL | `raw-milvus-native-3.json` 9/9，冻结源码组件，dependency `4c0d4e…`；前两 FAIL 保留 |
| 真实单页 PDF 普通 CUA → Raw → Qwen → PG/Milvus → 原件定位 | `raw-browser-controlled-pdf-1`；执行 `a2d6b8…`，任务正常 SUCCEEDED，读回5/5 |
| 真实67MB DOCX 五分片 CUA、同任务恢复、结构和下载 | `raw-browser-multipart-1`；首次 `4b3ff8…` 完成 SQL grammar FAIL保留；修复后 `28ff064…` 同file attempt2/epoch2 SUCCEEDED，读回5/5、实际下载字节5/5 |
| 先正常API真实上传两片、再CUA同SHA/元数据重选完成 | `raw-browser-multipart-resume-1/result-1.json`7/7：实际同session5219a813、原两片MD5仍在最终5片清单；67MB wholeSHA VERIFIED、普通Raw首轮SUCCEEDED、PG/MinIO/本地tokenizer/cache/Milvus读回5/5，绑定28ff。API准备与CUA分别记录，不称全纯CUA；该首次续传的精确PUT次数未测，mc多行JSON探针失败保留 |
| 原件已发布但任务终态失败后的正常重试 | 同file93f7e2f4，当前source/projection检查后完成；原发布scope/epoch1保持不可变，不伪造epoch2发布或重复模型成功 |
| 当前28ff实际 multipart HTTP并发/权限/坏MD5/坏wholeSHA | `raw-multipart-api-2.json` 22/22；故意坏SHA隔离不是成功解析 |
| 新源码严格终态与分片原生组件 | `raw-multipart-native-3.json` 21/21，dependency28ff/source overlay；未重复600签名等待 |
| 当前fd7真实TCP断线、合并进程崩溃和完成期限恢复 | `multipart-real-crash-native-2.json` 7/7；实际只传65536/16777216声明字节后断开连接，MinIO没有已完成片，同session重传与短末片合并后完整SHA一致。另一独立JVM在MinIO合并成功、PG尚未finalize时退出71；06:29:30.598318Z→06:39:30.598318Z真正600秒期间owner/deadline保持不变，新owner根据HEAD恰好创建一份版本/任务/outbox，重放不重复，先前完整原件SHA不变。全部类/迁移取fd7immutable，独立schema `multipart_crash_077606130e50`保留；只验证传输与READY任务，未宣称此fixture已解析/索引。首轮probe编译FAIL保留 |
| 真实600秒签名过期/续签 | native2实际603.9秒原生403，续签200，同24h会话；native2原23个check中一项断言不足，errata只计22可用，不能照抄23全通过 |
| 默认可读失败、ADMIN折叠原诊断 | `readable-failure-cua-1.json`5/5，实际INPUT_ERROR记录；非ADMIN本批未另做CUA |

保留的普通失败没有覆盖：首次 terminal SQL join 左侧重复 version_id；首次原件查看懒加载资产缺失（普通 build 曾覆盖 live dist，恢复旧hash资产并改用独立outDir/原子发布后正常下载）；测试夹具漏必填字段、记录不足断言及前期依赖/codec失败。后台正常成功与失败均以原记录和当前资源读回为准。

## 导学的12个上传场景证据映射

`scripts/record-upload-case-matrix.py`只整理已经执行的证据，不启动测试、不增加断言数。`upload-12-case-matrix-1.json`保留合并恢复尚在真实等待时的10/12状态；`upload-12-case-matrix-2.json`在上述原生故障恢复完成后记录12/12场景机制各自证据和构建边界。

| 场景 | 已实际验证的范围 |
|---|---|
| UP-01 正常上传 | 普通CUA真实PDF上传、MinerU/Qwen执行、PG/MinIO/Milvus原件读回 |
| UP-02 短末片 | 原生93字节末片；普通67MB文件最终五片清单 |
| UP-03 断线恢复 | 真实TCP在65536/16MiB中断；同session补传与全文件SHA，不称本次为浏览器断线 |
| UP-04 重选核验 | 正常API拒绝不同全SHA；普通CUA同SHA/元数据续传，前两片来自正常API准备 |
| UP-05 重复完成 | 正常API并发/重复完成，原生24并发只产生一份版本/任务 |
| UP-06 合并后崩溃 | 原生真实MinIO合并后独立JVM退出71；完整600秒固定期限后HEAD恢复 |
| UP-07 URL过期/补签 | 原生实际603.9秒等待，旧URL403、新URL200，同24小时会话 |
| UP-08 越权 | 另一合法上传者不能查看/签发本会话；未称全多租户认证 |
| UP-09 校验不符 | 原生错误MD5拒绝；正常Raw worker错误wholeSHA在模型/发布前INPUT_ERROR |
| UP-10 分片缺失 | 正常API/原生缺片不能进入COMPLETING |
| UP-11 取消 | 普通API/原生只Abort自身未完成上传，保留另一完整原件 |
| UP-12 过期清理 | 隔离PG注入过期边界后真实MinIO清理；未等待实际24小时、未观察生产小时触发 |

```sh
python3 scripts/record-upload-case-matrix.py \
  --evidence-dir <保留上述实际报告的目录> --output .tmp/manual-upload-case-matrix.json
```

上述12项有不同的正常页面/API/原生组件层级和构建时间，不能合称同一已部署JAR上全12项端到端浏览器通过。12例属于导学单独工程验收，也不计入240题主评测及历史5.1→1.8数据。

扫描 PDF 与多页跨页表格现已进入实际专项：固定同一完整两页原件（第一页电子文本，第二页没有可抽取文字、仅一张扫描图），独立 Gold 记录多层表头、四条原行、金额/条件/单位及位置。CPU2、CPU4、MPS三轮在完整600秒内均未完成视觉推理，原件与失败记录保留，不能当解析成功。第二轮清理异常曾把到期结果标为 `PARSER_WORKER_LOST`，该真实原 ledger 未被重写；后续修复只处理自身已经释放的进程组检查。第三轮MPS正确返回`PARSER_STAGE_TIMEOUT`且进程组释放，601.496秒包含清理和轮询；硬件支持通过不等于表格正确。

浏览器暂停发生在 hash 2%，不能称为已传 part 后的网络断点续传。另一次浏览器重选续传已实际完成（同session2片预置→CUA5片终态）；准备是正常API，完成是CUA。精确“浏览器只补PUT3/4/5”的协议计数尚未取得，不能用mc未识别多行JSON的空trace代替。完整条目演化、贡献升阶与查询质量属于独立批次。

## 冻结的30个Stream案例与未完成的大样本实验

`scripts/freeze-stream-30-cases.py`先冻结30条独立原件/故障位置/预期终态：领取后退出、结果写完未ACK、重复投递、过期接管、Redis故障、毒任务各5条，开发20/留出10。`scripts/test-stream-30-native.py`核验 manifest 与不可变JAR SHA后才运行真实 PG/Redis/MinIO/Milvus；真实独立JVM退出、隔离网络断线、600秒DB期限都保留日志和任务事实。case数量、执行数量、通过数量与断言数量分别记录。初始化使用明确合成向量与 ACTIVE 来源fixture，只验证协议/发布，不冒充模型、完整解析或效率结果。

```sh
python3 scripts/freeze-stream-30-cases.py --help
python3 scripts/test-stream-30-native.py \
  --jar-path <冻结时的immutable-JAR> --manifest <冻结manifest.json> \
  --output .tmp/manual-stream-30-native.json
```

`raw-stream-30-native-1.json`保留测试脚本编译失败；`raw-stream-30-native-2.json`保留初始化字段错误，两轮均没有案例执行通过。第三轮已触发全部固定故障并进行真实600秒等待，但非计划PG后台进程SIG13/Milvus etcd lease丢失导致读回中断，该轮原报告仍为FAIL。依赖恢复后，先纯只读检查原30份PG/Redis事实守恒：102条检查通过、19份终态保留、11份仍是原expired owner，未改状态；再从同schema/对象/streams正常claim11份原任务，完成30份PG/MinIO/Milvus/PEL验证。续跑`raw-stream-30-resumed-native-1.json`记录30/30（20开发/10留出）、246条断言；没有重新造数据、直接SQL改成功或宣称原失败已通过。非计划基础设施事故没有算成第31个计划案例。

```sh
# 不更改任务状态/期限/ACK，只读取失败原schema与原stream的灾后守恒。
python3 scripts/read-stream-30-recovery.py \
  --prior-report <原raw-stream-30-native-3.json> --output .tmp/manual-stream30-recovery-readback.json

# 仅在原11份仍未接管、原件与schema保留时继续；完成后的数据不能再伪装成同一次待恢复。
python3 scripts/resume-stream-30-native.py \
  --prior-report <原raw-stream-30-native-3.json> --jar-path <原3e981429不可变JAR> \
  --output .tmp/manual-stream30-resume.json
```

日后从头重跑使用新的冻结manifest和新输出目录；复用原案例续跑只能对原待恢复事实进行，不清空/重建旧schema。不能用此前14条断言或105个PEL消息代替30个案例。

切块40份资料×9组配置已在不可变`328635cb…dc1f33f0`上按原冻结Gold完成360/360；首轮160通过/200失败保留，详见[切块与Hash实验](chunk-and-hash-experiments.md)。Hash100份开发文档×0/1/5/20/100%正文变更的真实编码耗时对照，以及同题单/同模型质量对照尚未执行。已有真实Qwen缓存小闭环及合成缓存组件证明不能替代这些大样本实验，也不代表600/80参数已胜出。

## 首次入库前的资料分类

初始上传、暂存批次和分片会话均接受可空`security_level`，范围沿用既有10..127规则，仅管理员可以显式指定或清除请求分类。页面在“访问范围”下正常选择，无需用户填写JSON。未指定时，新资料默认10；现有逻辑资料的新版本继承既有分类。该值在预留版本及首个Raw发布前持久化，不能先公开发布再补权限。暂存编辑使用`clear_security_level=true`恢复继承；不能同时传新值与清除标志。返回JSON省略可空字段时同样表示继承，不代表默认公开已持久化。

`e0e37b61…bd4b03f4`通过完整后端回归253项（另1项真实模型smoke明确未启用），正常idle替换后，实际Chrome使用既有等级30管理员选择保密资料，完整分片上传/后台Raw入库首轮成功。独立原件`HT-INITIAL-ACL-20261003保密验收.md`的SHA为`95f798402e373a8c81b857667fc4297862cc928002888e17007f0a0384263550`；版本`807b2399-098b-4526-bda4-28e22c5d730d`初始分类30、ACL1，MinIO原件字节一致，Milvus全部1个当前成员可读且公开等级10结果为零。资料页实际显示金额37万元及“未取得合法发票，不得付款”，公开用户同名称搜索无结果。该独立开发原件不计入正式QA/Hash主集。

实际HTTP报告`raw-upload-classification-deployed-http-3.json`记录21条检查通过：正常表单和typed PATCH保存30、管理员清除回继承、非法9/128拒绝、清除与值同传拒绝、已有两份财务资料及新原件的管理员200/公开用户403。前两轮runner因可空字段省略/当前普通用户上传全局禁用的错误假设而失败，原报告保留；没有改变全局上传权限来凑通过。当前部署普通USER自有暂存文件的分类专项因为上传全局关闭未在HTTP测，独立原生组件覆盖，不把全局403混称该专项通过。

低等级管理员首轮实际合并拒绝也保留：原`acceptance_admin`当前等级10，未提高账户权限、未重写完成状态，旧上传会话和对象仍保留。后续仅把相同clearance检查前移到create/PATCH并禁用页面过高选项；源码组件`raw-upload-classification-native-4.json`26/26使用真实PG/MinIO/Milvus及合成向量。该源码随后完整回归254项通过、1项真实模型smoke明确未启用，源前后不变，生成`3854403e…3c393f19`；正常idle替换和最终web原子发布后，`raw-upload-classification-deployed-http-4.json`28/28检查通过，低等级ADMIN早期创建会话/批次及自有暂存PATCH均403，两个拒绝创建请求的原生PG会话/文件数量不变，合法未指定范围仍可暂存。普通Chrome等级10账号实际显示内部/保密/严格保密选项已禁用。所有初始化原件、SQL、失败/通过报告和截图均在本任务`work/resume-20261003/`。

```sh
# 仅读取已完成的独立浏览器原件；正常API创建保留的STAGED否定测试，不触发Raw模型。
python3 scripts/test-target-initial-classification.py \
  --logical-name HT-INITIAL-ACL-20261003保密验收 \
  --early-clearance \
  --output .tmp/manual-initial-classification-http.json

# 只读原件与当前Raw索引；readback-manifest必须绑定保留原件SHA及预期分类。
python3 scripts/test-formal-raw-import-audit.py \
  --manifest <保留的raw-classification-cua-fixture-1/readback-manifest.json> \
  --output .tmp/manual-initial-classification-storage.json
```
