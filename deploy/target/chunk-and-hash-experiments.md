# 结构切块与 Hash 的固定实验

脚本只使用专用 `CHDEV` / `HASHDEV` / `HASHBENCH-DEV` 合成资料，不复用正式问答留出原件。先冻结原件、业务 Gold、案例分类和 SHA，再执行；失败报告保留，不根据留出结果改 Gold。

## 40 份开发资料 × 九组切块参数

当前先完成源原生 HTML / CSV 阶段：合同、补充协议、验收说明、财务台账各 10 份，共 40 个文档案例；目标 400 / 600 / 800 × overlap 0 / 80 / 120，共 **360 次文档参数执行**。硬上限始终 900，尾块阈值 150，表格数据行 overlap 始终 0。

本阶段使用生产 `TextDocumentParserAdapter`、`RawKnowledgeBuilder`、`RawChunkProfile`，以及冻结的六份 Qwen Embedding tokenizer 文件、真实 DJL tokenizer。Gold 包含完整金额、单位、条件、否定语义、表头、每行每个单元格和原位置，并保留每份三个固定业务问句。

第一轮绑定不可变部署 JAR `71e2b25…45bd182`，实际执行 360，160 通过、200 失败：

- 155 次失败暴露字符边界把分号 / 小数点当成安全句子边界，可能拆开付款条件与金额。
- 45 次失败来自五份 CSV × 九组参数：原 CRLF 在计算位置之前被变成 LF，记录的 offset 指向归一化副本。

诊断没有改原 Gold。修复使用 JDK `BreakIterator` 句子规则；超过目标但能放进 900 上限的完整句子可保持完整。真正超过硬上限的单句仍有界分片、保留相同来源身份，不宣称任意长条件都能装进一块。算法策略标识升为 `raw-structure-v3`，避免旧 checkpoint 被当作新切块策略使用；默认参数不改。

CSV 位置现在由 Commons CSV 读取原始换行取得，保留 BOM 前缀偏移并明确 UTF16 character offset。原记录中的单元格值保留 CRLF；输出表格单元格独立转成 LF，以保持已有显示契约。完整后端第一次验证发现这项兼容差异，失败报告保留，旧测试断言没有被改成接受不同结果。

修复后的 source-overlay 第二轮 360 / 360 通过；针对 Builder / 新 CSV / 原 Routing 的真实 JUnit Platform 回归 23 / 23 通过。完整构建与正常部署之后，第三轮使用不可变 JAR `328635cba268728ba754f4c4c7fd7778d512658050dfb1c750a99291dc1f33f0`，不编译工作区生产源、原 Gold 字节不变，实际 **360 / 360 再次通过**。下面数值与第三轮读回一致。

| 目标 / overlap | 块数（40 原件合计） | 实际待编码输入 token | 最大片段 token |
|---|---:|---:|---:|
| 400 / 0 | 700 | 257250 | 466 |
| 400 / 80 | 715 | 258735 | 428 |
| 400 / 120 | 715 | 264365 | 428 |
| 600 / 0 | 390 | 209285 | 604 |
| 600 / 80 | 390 | 210185 | 648 |
| 600 / 120 | 390 | 212095 | 666 |
| 800 / 0 | 300 | 195285 | 784 |
| 800 / 80 | 300 | 195285 | 784 |
| 800 / 120 | 300 | 197480 | 784 |

token 包含实际模型文档模板、标题前缀和重复表头。此表没有执行模型编码、检索召回或问答；问答质量、必要证据召回和问答耗时仍未测，不能据此宣布 600 / 80 或其他配置胜出。这 360 次执行是主 240 用例之外的补充实验。

可重跑，输出目录每次用新名称：

```sh
python3 scripts/freeze-chunk-40-cases.py --output .tmp/experiments/chunk40-gold-01
python3 scripts/test-chunk-40-native.py \
  --jar .tmp/target/artifacts/<实际已验证SHA>/evokb.jar \
  --fixtures .tmp/experiments/chunk40-gold-01 \
  --tokenizer-manifest .tmp/tokenizer/qwen3-vl-embedding-9f2f7e710d6d-1/manifest.json \
  --output .tmp/experiments/chunk40-native-01.json
```

默认只加载指定 JAR，绝不编译工作区生产源。显式 `--source-overlay` 仅用于未部署的修复组件验证，报告标注不能算部署通过。每份原件 / 每组参数的完整 chunks、来源 block、Gold 读回保留在报告旁的 `.documents/`。

## 主 Hash40 机制与独立 100 份开发原件

固定 Hash40 由五组各八个独立机制案例组成：正文归一化、结构改版、完整编码身份、坏向量与不可变并发缓存、来源权限 / 版本与受控发布。开发 24、留出 16；原清单与分组已冻结。在已部署的 `328635cb…c1f33f0` 不可变 JAR 下，实际 **39 通过、1 无效夹具**，开发 24 / 24、留出 15 / 16。机制向量显式为 fixture，不替代真实 Qwen 编码或收益对照。

无效项 `HASHDEV-5-05` 原本期望同正文生成新来源版本，却给了字节完全相同的原件；原生 `uq_document_sha` 正确拒绝重复制造版本。不能修改冻结 Gold / 留出分组，不能放宽约束或把同原件幂等复用算成新版本通过。失败记录与独立 schema `raw_hash40_4887d355e6c0` 保留，主集分母仍为 40。

另加一项明确披露的开发补证 `HASHDEV-ERRATA-VERSIONFORMAT-01`，只把原件 LF 改为 CRLF。原正常 Raw 应用服务创建版本、保存 MinIO 原件 / 解析产物 / Evidence、受控发布 Milvus 并完成任务：三轮分别为初始 v1、新格式 v2、精确重复 v2，实际 fixture 编码输入为 **3 / 0 / 0**。三轮 SUCCEEDED，晚到旧 claim 均拒绝；MinIO 原件 SHA、新旧不同来源块、只有 v2 可检索均通过。初轮长 synthetic ID 超过数据库长度、第二轮测试误要求成功后 owner 必须置空，两次补证失败也保留；第三轮 1 / 1 通过。此项使用真实持久组件和生产正常 Raw 服务，但明确 fixture 向量、零模型请求，独立于主 240 和原留出 16，不能将主集改写为 40 / 40。

另有独立 100 份开发原件，每份三项独立正文单位（付款条款、质保条款、台账正文），共 300 单位。五种比例按**变更的正文单位 / 固定 300 单位**定义，分别为 0 / 3 / 15 / 60 / 300。此口径不是字符、字节、token 或文档比例，不能混称。每种版本都保留发票、书面验收和缺材料不能付款的 Gold；对应金额 / 质保值按事先冻结的规则改变。

正文 Hash 不取代完整实际编码输入身份。移动段落、改来源版本或权限，即使可以复用计算结果，仍保留不同的来源块与检索权限。模型 / tokenizer revision 的 synthetic 字段用例只能证明失效协议，没有加载新权重，不算真实迁移。

这 100 份原件的 baseline 与五比例版本已真实离线执行 **600 次生产解析 / 切块**，六份冻结 tokenizer 与真实 DJL token 计数、金额 / 条件 / 否定 / 数据行 Gold 全部通过，使用同一 `328635cb…c1f33f0` JAR。下表是实测得到的待编码输入集合，**模型调用数为零**，不是实际编码次数、收益或可检索时间。

| 正文单位变更比例 | 全量重建待编码输入 | 与 baseline 不同的输入 | 全量输入实际 token | 不同输入实际 token |
|---|---:|---:|---:|---:|
| 0% | 300 | 0 | 33000 | 0 |
| 1% | 300 | 3 | 33000 | 220 |
| 5% | 300 | 15 | 33000 | 1443 |
| 20% | 300 | 60 | 33000 | 6555 |
| 100% | 300 | 300 | 33200 | 33200 |

正式100份全量 / Hash 编码与端到端时间对照仍未完成。共享 Qwen 的现有CPU运行策略已由独立真实输入验证后，协调连续窗口先执行预先冻结的两份开发原件小对照。解析没有被省略；不得从表格或尚未完成的小对照推算已测百分比收益。

可执行对照 runner 为 `test-hash-efficiency-native.py` / `RawHashEfficiencyProbe.java`。两组分别建立新的独立 PG schema 与 Milvus database，用生产 `RawVectorCache.none()` 作全量基线、`JdbcRawVectorCache` 作 Hash 方案；实际 parser / Builder / token / MinIO 原件读回 / owner claim / 原生 stage / 受控发表 / 正常任务完成 / 全部当前 chunk 检索读回相同。每种正文比例都从冻结原件重读重解析；相同原件复用现版本但明确重建索引，不制造违反原件唯一约束的新版本。每文档先后顺序交替，冷 baseline 成本另报，所有对象与失败保留。

真实模式使用实际 whole-input 模型适配器，经独占 loopback 代理记录本次任务精确 HTTP 尝试、完整输入 Hash、状态与响应字节 Hash，不拿其他项目的全局日志计数冒充本次成本。开始时先以冻结计划中的一个完整DEV输入取得同次模型响应的 `encoding_identity`，这一次额外模型调用和耗时单列，计入总成本。实验缓存身份绑定真正下游端点及此实际 profile SHA：model / loaded revision / native tokenizer / preprocessor / pipeline / packages / CPU策略 / 原权重精度 / input type / 输出维度 / normalization 全部进入身份。每次成功响应再核对完整输入 SHA 和返回 float32 向量 SHA；运行身份变化、缺证明或向量不符时，保留实际 upstream HTTP200、原响应与成本，拒绝向编码器交付该响应。旧的 `/ready` 配置不替代实际认证。

该身份守卫属于本实验 runner：不会给历史向量补造证明，也不宣称现有产品所有缓存已经接入该动态合同。正常认证响应原字节转发；身份校验拒绝才明确返回实验入口502。原模型 / revision / 权重不变，服务参数未经本脚本修改。后续发表失败前已经发生的编码成本也保留，预检后 baseline 为冷缓存、已加载模型的基线，不能冒称冷模型首次加载速度。

脚本机制试跑采用明确 fixture 向量。首轮两份开发原件 × baseline + 0 / 1 / 100 三个比例 × 两组，实际 16 / 16 正常读回；补充五比例轮实际 24 / 24。另明确选择预先冻结的开发原件 `HASHBENCH-DEV-063` / `069`，两组完整五比例再执行 24 / 24：Hash 在 baseline / 0 / 1 / 5 / 20 / 100 的实际 fixture 接受输入分别为 6 / 0 / 2 / 2 / 5 / 6，全量每轮 6；覆盖部分命中，原 Gold 未改变。这些模型请求全为零，只证明 runner 正确，没有真实时间收益。首轮 `Candidate.content()` 编译失败保留。透明 HTTP 代理另以本机显式 fixture 服务验证 200 / 503、完整 body / purpose / authorization 转发和独占记录，7 项检查通过；它不是 Qwen 推理。正式百份完整对照仍未执行。该脚本测量原生组件从读取原件到可检索的耗时，不能混称浏览器上传或问答质量的耗时。

```sh
python3 scripts/freeze-hash-development.py --output .tmp/experiments/hash-development-01
python3 scripts/test-hash-40-native.py \
  --jar .tmp/target/artifacts/<实际已验证SHA>/evokb.jar \
  --fixtures .tmp/experiments/hash-development-01 \
  --tokenizer-manifest .tmp/tokenizer/qwen3-vl-embedding-9f2f7e710d6d-1/manifest.json \
  --output .tmp/experiments/hash40-native-01.json
python3 scripts/test-hash-inputs-native.py \
  --jar .tmp/target/artifacts/<实际已验证SHA>/evokb.jar \
  --fixtures .tmp/experiments/hash-development-01 \
  --tokenizer-manifest .tmp/tokenizer/qwen3-vl-embedding-9f2f7e710d6d-1/manifest.json \
  --output .tmp/experiments/hash100-input-plan-01.json
python3 scripts/freeze-hash-source-format-erratum.py \
  --main-fixtures .tmp/experiments/hash-development-01 \
  --output .tmp/experiments/hash-source-format-erratum-01
python3 scripts/test-hash-40-native.py --normal-format-supplement \
  --jar .tmp/target/artifacts/<实际已验证SHA>/evokb.jar \
  --fixtures .tmp/experiments/hash-source-format-erratum-01 \
  --tokenizer-manifest .tmp/tokenizer/qwen3-vl-embedding-9f2f7e710d6d-1/manifest.json \
  --output .tmp/experiments/hash-source-format-native-01.json
# 先独立确认runner，fixture结果不可声称实际模型收益。
python3 scripts/test-hash-efficiency-native.py --fixture-vectors \
  --max-documents 2 --rates 0,1,100 --max-seconds 180 \
  --jar .tmp/target/artifacts/<实际已验证SHA>/evokb.jar \
  --fixtures .tmp/experiments/hash-development-01 \
  --input-plan .tmp/experiments/hash100-input-plan-01.json \
  --tokenizer-manifest .tmp/tokenizer/qwen3-vl-embedding-9f2f7e710d6d-1/manifest.json \
  --output .tmp/experiments/hash-runner-fixture-01.json
# 仅在共享模型窗口协调完成后运行；显式actual调用，没有fake fallback。
python3 scripts/test-hash-efficiency-native.py --real-embedding \
  --max-documents 100 --rates 0,1,5,20,100 --max-seconds 7200 \
  --jar .tmp/target/artifacts/<实际已验证SHA>/evokb.jar \
  --fixtures .tmp/experiments/hash-development-01 \
  --input-plan .tmp/experiments/hash100-input-plan-01.json \
  --tokenizer-manifest .tmp/tokenizer/qwen3-vl-embedding-9f2f7e710d6d-1/manifest.json \
  --output .tmp/experiments/hash100-real-01.json
```

`test-hash-40-native.py` 建独立 PG schema / Milvus database，使用实际缓存表、受控 Raw claim / 发布与原生索引过滤，所有对象保留；不清业务库、不请求共享模型。`test-hash-inputs-native.py` 只保存实际生产解析 / 切块 / native token 得到的输入与计划数量，模型调用数为零。实际模型编码条数、HTTP 失败重试、端到端可检索时间与最终块集合须由后续真实对照记录，不由计划数量推算成实测收益。

当前证据位于本任务 `work/resume-20261003/`：`raw-chunk-40-cases-1/`、`raw-chunk-40-native-1.*`、`raw-chunk-position-diagnostic-1.json`、`raw-chunk-40-overlay-native-2.*`、`raw-chunk-40-deployed-native-3.*`、`raw-chunk-focused-junit-1/2.*`、`raw-chunk-source-freeze-1/2.json`、`raw-hash-development-1/`、`raw-hash-40-native-1.*`、`raw-hash-100-input-native-1.*`、`raw-hash-source-format-errata-1/`、`raw-hash-source-format-errata-native-1/2/3.*`。同一完整双页扫描的 CPU2 / CPU4 / MPS 三轮实际失败另见 [MinerU README](../mineru/README.md)，不会被此 HTML / CSV 矩阵算成通过。

首次入库分类改造正常部署`3854403e…3c393f19`后，按严格“input-plan与被测JAR必须同SHA”规则重新冻结100份开发资料的600个原件输入，`raw-hash-100-input-native-2.json`通过且模型调用0。`raw-hash-100-input-rebind-1.json`错误地把每次实际测量的解析耗时纳入输入身份，比较失败保留；第二份明确仅排除`nativeParseAndChunkMillis`，原件SHA、完整模型输入SHA、tokens、业务Gold及所有变更数量完全一致。不能从这两轮解析耗时推导模型性能收益。

同一新不可变JAR上的两份预选开发文档`HASHBENCH-DEV-063/069`又执行了完整baseline＋0/1/5/20/100%两种方案，`raw-hash-efficiency-runner-fixture-6.json`24/24原生来源执行通过，所有模型调用为0；第五轮写错显式开发ID在预检即拒绝，0来源执行，失败保留。FULL合成编码基线及每档各6项，HASH为6/0/2/2/5/6，共57项合成向量，只说明缓存/原件/切块/受控发布与实际Milvus读回链可运行，不是实际Qwen效率。下述真实小对照另有实际模型调用，不把此fixture结果挪作真实结果。正式100份对照仍未执行。

动态编码合同补证：`raw-hash-atomic-contract-1.json`对上一份已保存真实响应进行纯重放，10项通过，包含缺认证、输入变化、坏向量、配置变化、维度/有限值/索引/数量拒绝；零新模型调用。`raw-hash-trace-contract-2.json`以本机显式fixture HTTP验证认证有效时原字节保留、缺认证/配置变化时真实upstream200成本保留并拒绝client响应，17项通过，零共享模型调用。改后的原生runner又在 `raw-hash-efficiency-runner-fixture-7.json` 完成24/24来源执行，向量明确fixture。这些检查独立于真实模型小对照和正式100份效率结果，不进入主240的案例分母。

## 两份开发原件的真实编码小对照

`raw-hash-efficiency-real-subset-1.json`在实际已部署`3854403e…3c393f19`不可变JAR与`raw-hash-100-input-native-2.json`下执行，两份预选开发资料、baseline＋五档比例、两种方案，共**24/24来源执行通过**。逐来源236项断言和6项atomic身份检查另列，不将它们说成242个案例。实际模型为原`Qwen3-VL-Embedding-2B`、revision`9f2f7e…`、原BF16权重，CPU `8/1 expanded-kv-fp32`；实际镜像`80802c…`、源码`41c4c4…`、同容器同进程，实验没有改变这些参数。

模型预检用冻结DEV063的第一个完整chunk，82个native tokens、HTTP200/1向量、12.872秒。正式小对照另有22次专属HTTP，全部200，57个有效输入；合计**23次实际HTTP、58个有效输入**，没有观察到重试或认证拒绝。每个完整input SHA、同次float32 vector SHA、整个实际profile SHA均和native编码器接收值一致，源/脚本冻结前后相同；MinIO原件SHA、PG受控成功终态、当前Milvus全部物理块读回通过。

| 方案 | 来源执行 | 有效编码输入 | 实际编码输入tokens | 来源处理总耗时 | 最终缓存行 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 全量 | 12 | 36 | 3964 | 452.605秒 | 0 |
| Hash | 12 | 21 | 2178 | 451.768秒 | 21 |

总wall时间933.028秒包含额外预检、组件初始化/编译等，不等于任一方案的上传时延。缓存确实减少了这两份资料的编码输入，但两组总处理耗时几乎相同，**不能声称显著加速**。0%变化的两份Hash原件没有模型调用，仍重读/解析/受控发表且完整可检索；其他比例逐档真实耗时可从report.metrics读取，其中1%/20%有Hash更慢的实际结果，均保留。当前本机有并行普通组件负载（`raw-hash-runtime-observation-1.json`），不是模型微基准；两份预选资料的变化覆盖也不等于全100份的每档总体变化比例。未测问答召回/模型语义质量，不属于正式100份效率实验或主240案例。正式100份需要更长连续模型窗口，不以预计2478项编码冒充已执行。

生产动态身份审计另见`raw-production-encoding-identity-audit-1.json`及可重跑的`audit-production-raw-encoding-identity.py`：当前产品缓存已绑定实际配置的model/revision、本地六资产、prompt/rendering和完整输入；尚未把新服务的实际packages/pipeline/CPU算术策略带入其动态profile，响应DTO也未保留atomic字段。新CPU算术保持原模型、revision、BF16参数和模板，不能把这个精确计算身份缺口混称静默模型替换。缓存命中仍必须经owner/epoch/600、当前source/ACL和短发布事务，不取得额外读写权限。本节实验通过不宣称此生产身份缺口已经改完。


100份正式开发对照的可续跑编排现在由 `run-hash100-batches.py`、`test-hash100-resumable-native.py` 和 `RawHashResumableProbe.java` 提供。`--prepare-only` 校验原100份/600原件及实际token计划，冻结20组×5份、每组60来源case，模型请求零；冻结预计值仍不是执行值。每个进程尝试使用新输出stem，保留append-only尝试日志/模型请求启动日志、旧checkpoint、全部费用和失败。完成的来源/批次跳过；不完整来源续用原schema、MinIO/Milvus空间及原file/epoch，生产Repository只接管真实到期的600秒任务并尊重5次预算/实际退避，绝不盲重置RUNNING或新建file绕过隔离。已完成但因进程退出未落最终报告的来源再做原发表的原件/PG/Milvus读回，不重编码补造成本。

首次原生机制证明 `raw-hash-resume-fixture-3.json` 受控停在2/12来源（整体明确INCOMPLETE，不算12通过），独立新JVM `…fixture-4.json` 在原两个schema继续，只执行剩余10个，最终12/12正常读回，旧baseline文件/attempt1/epoch1保留，0模型。此前两次操作者提供不存在的示例fixture路径均在创建schema前失败，独立记录，不隐藏。`raw-hash-100-resumable-plan-1/plan.json` 是同385 JAR/Gold下的20组原冻结计划；后续仅检查点成本/编排脚本改动另存plan-2/plan-3，均尚未启动实际100模型对照。

真正Runtime.halt(73)已单独完成：原file `2a25b252…` 在12:29:07.675781Z领取，保留原12:39:07.675781Z期限；独立新JVM等待真实600秒，原epoch1记录RAW_STAGE_EXPIRED后，由正常Repository领取attempt2/epoch2，完成原schema中12/12来源案例、13次实际执行、0模型。`raw-hash-resume-crash-lineage-3.json`的12项只读PG/原发表/Milvus谱系检查通过；此前读取格式错误及对0%同版本后续正常重发表的错误oracle失败独立保留。最初helper只计续跑进程内时间，新helper改为包含原尝试及停机等待，并保留每次调用成本；更新后fixture编排复验另列，不把旧报告时长冒充完整恢复wall时间。主Hash39/40+INVALID、额外ERRATA、360离线切块、两份真实24来源和脚本试跑均各列分母。
