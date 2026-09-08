# PostgreSQL + Milvus 本地环境

本环境按 2026-09-21 整理的《项目全解-EvoKB.md》实施；该文档 SHA-256 为 `21095750b4fb0b0338433c10b3956e57deb1237ae2b8cf201b1d71ae19f4bbe5`。旧源码中的 MySQL、OpenSearch、Neo4j 部署保留用于排查，不能作为新版验收结果。

当前主链路使用 MinIO、PostgreSQL、Milvus。图谱存 PostgreSQL；所有 RAW、SUMMARY、CONCEPT、WIKI 投影共用 Milvus 的 `knowledge_chunks`。使用 Qwen3-VL-Embedding-2B 的 1024 维编码和 Qwen3-VL-Reranker-2B，聊天模型固定为小模型 `gpt-5.6-luna`、大模型 `gpt-5.6-terra`。Raw 已接入 Redis Stream、PEL、持久任务账本及终态 ACK；30 个独立 Stream 故障案例已在原任务灾后续跑全部通过，组件输入使用合成向量，不能充当实际模型质量验收。

## 当前本地发布与验收（2026-11-04）

最新运行已更新为 `03fa098076d2af0913a980e1bf9175ce0b66f15854b21e335a713da4d90b4d18`，2026-11-04 03:32:22 正常启动。完整 431 项＝430 通过、1 条件 smoke 跳过，0 失败/错误，源码和验证工具清单稳定；同包真实 PostgreSQL 上下文记录及 12 路并发共 12 项通过。

按真实 Raw 检索块保持最多 8 块、6000 正文 token；块内每段原文完整保留并分别引用，不再把解析段落错算成独立检索块。QUERY_CONTEXT_SELECTED 持久记录各轮真实块成员、来源/路由、token/字符数及上下文 Hash；原文位置递归按键排序，跨进程可精确复算。当前正常 CUA 同题会话 `93134ffa-0bc5-4589-8091-f4aeb82214e0` 第一轮真实 ANSWERED，原合同 30 日、补充 45 日、共同前提及不能证明实际履行均正确，13 项会话/预算核对通过。两个独立 JVM 各 10 项实际 PG/Milvus/冻结 tokenizer/精确上下文 Hash 复算通过；反馈和原件下载走正常页面。当前会话反馈/三份原件 ZIP 字节/PG/实际新 Wiki 展开与唯一贡献等 19 项核对通过，重复下载没有重复贡献；不能据该单例推断正式大集通过。

旧 a6b6195d 与 0c8a5506 的 INSUFFICIENT 和负反馈保留。前一包 6d8 上业务问答、三份原件与真实 Wiki 展开贡献 19 项通过，但发现 Map 无序导致 Hash 不能跨进程复算；该失败独立保留，未修改旧快照。新增实现、夹具说明与复跑见[完整检索块与精确上下文](../../docs/development/2026-11-04-whole-chunk-context.md)。Wiki 最终前端发布经 lint/type/build、53 源文件/25 HTTP 资源 Hash 核验；实际 390px 页面及原文弹窗、1280px 桌面检查，长标题和按钮无横向溢出。
前一阶段`1095ceade15fe9ff8d78993f89d80495438ed97637acf917d6653a8490b9d05a`在01:39:45启动；完整420项＝419通过、1条件跳过，真实PG图谱47项通过。新增Wiki完整候选分批核验，与Summary共用每批4条上限，原120秒HTTP/600秒阶段期限不变。原任务重启后保留截止时间，按到期恢复/退避于01:45:12领取attempt4，并于01:49:47真实发布12条claim，3批Luna核验；命令/原始模型回执/完整输入/逐条审计/PG/MinIO/Milvus/API/重复POST等11项通过。原27条提案与成功12条提案不同，不冒称固定输出耗时A/B；也不当自动稳定条目晋级证明。见[Wiki核验批量与原任务恢复](../../docs/development/2026-11-04-bounded-wiki-grounding.md)。下方f77问答是其原产物上的真实结果；与1095的21个问答/范围/HTTP边界class字节一致，不倒填成新执行。

前阶段不可变JAR为 `f77fa4e0c464e5bf4585b71feb58d07e0546d590b2c0333decd8af6963689cbd`，00:50:23正常部署，入口 http://127.0.0.1:18481 。完整416项＝415通过、1条件smoke跳过，0失败/错误，真实PG图谱47项通过。普通用户直接自然语言提问；新增交付前对象/引用范围/事实独立核验，失败复用原八轮预算补查。通用编号仅作软相关性，不替代模型关联和来源权限。503容量文字不再绕过原五次HTTP指数退避；后台任务继续原持久退避。

旧HT-2026-1102问题误引HT-ENTRY-104的900万元/15日已保留负反馈。新包首次同题因实际HTTP404失败，正常重试会话ca1dd27c…正确回答120万元/24个月不变、30→45日及双起算条件；CUA已解决反馈、参考原件实际下载，元数据8项和原件/PG/使用账本18项分别通过。独立Luna真实模型对照拒绝原误答、接受正确主体的旧真实回答；不把只读对照当新问答生成。404两次同配置诊断小请求均200，其服务端原因未确认，不冒称已修复。

真实11月4日午夜自动选出3条GRAPH并正常完成，实际合同图谱9项读回通过，生成包为当时e4c。新回答下载后合同条目真实贡献达到5，后台已形成WIKI候选，待下一正常业务日；当日日批次不重开，不改时间/热度。另提交正常管理员Wiki任务37e2b7a5…验证显式生成，排队及运行不计为已发布。最新细节、失败与验证边界见[对象范围与午夜验收](../../docs/development/2026-11-04-answer-scope.md)。下面历史段落保持原产物，不能冒领成新包逐一重执行。

## 2026-11-03 23:20 发布与验收（历史）

当前运行JAR为 `e4c46cf67ef7f687c6b93449174a04c5b528564fba19599a47e8dd48b5bc2a3a`，入口仍为 http://127.0.0.1:18481 。完整403项＝402通过、1条件smoke跳过，真实PG图谱47项通过。修复新增关联没有独立整合任务、模型遗漏关联仍假成功、锚点错误缺少可用反馈、过期修订反复重试，以及关系类型筛选SQL参数粘连500。四条pending已由真实后台完成；新包正常页面按类型＋日期筛选返回1条供货关系，API/只读SQL18项通过。原模型生成包538daa00的稳定条目12项、图谱9项实际物理读回分别通过，跨包只改图谱读类的字节身份另存，不能混淆生成版本。

详见[晚间关联与图谱验收](../../docs/development/2026-11-03-pending-integration-recovery.md)及[早些时候权限撤回验收](../../docs/development/2026-11-03-live-authority-revocation.md)。前端本批未改，Milvus/Qwen和600秒后台边界保持。完整正式基准、热度/午夜全矩阵仍未全部认证。旧MPS/普通CPU的PDF失败保留；11月3日已通过显式CPU expanded-kv-fp32配置修复，两次同原件15/15，正常浏览器上传的实际解析250.235秒，Gold15/15、PG/MinIO/Milvus/Redis24/24、普通用户原件5/5通过，当前解析端点18497。原件、权重、600秒不变；后端每个后续JAR不会重复冒领一次新PDF执行。下面各历史段的“当前”及PDF待修描述只对应各原批次。

## 2026-11-03 凌晨发布与验收（历史）

当前访问 http://127.0.0.1:18481/enterprise/wiki ，最终不可变JAR `ef29c903d74b4bb541db0f1c8b41f84f95aef053650e8c4d41c4162bc8558b89`，PID93814，00:11:45 +08:00正常启动。完整clean verify384项：383通过、1真实模型smoke未启用跳过、0失败/错误，252个后端文件字节稳定。前端正常lint/types/build并发布，当前legacy-reparse-web-manifest-2记录52源文件/25资产；旧hashed资产保留。

后台任务已按用户最新规则改为持久指数退避，保留历史和attempt，不再累计五次永久停止；单阶段600秒、有限HTTP重试与owner/epoch/完整输入ACL不变。同输入Summary复用一条正常链，继承最晚等待与原最大priority。原11/2合同同task attempt6真实成功，PG/MinIO/Milvus12项读回通过，原五次失败未变；补充协议正常检查点恢复，10/7旧Summary第7次成功。V24单独保存条目范围事实；既有正常投影命令在不重写描述的前提下为5个当前条目发布新修订，物理12项/API15项/旧修订审计3项通过，真实Chrome已打开独立范围原文。

**仍有失败和未测：** 固定两页PDF14/15及600秒超时未修成通过；两条10月旧Raw的原NULL身份/FAILED6保留，其真实MinIO原件已按正常入口生成可用v2，各PG/MinIO/Milvus/Redis23项读回通过；未认证旧行或批量迁移。F03旧种子文案与密级10不一致未改；新“交付与验收安排”有待关联来源，仍STALE。原异常Summary继续STALE/STAGED。正式质量大集、真实Wiki升阶、实际午夜调度、部分KB-03 I/O边界及新Graph的完整物理读回仍未覆盖。实际Graph任务成功记录不代替整条链验收。

设计与可重跑步骤见[后台持续退避](../../docs/development/durable-background-retry.md)，最终代码/部署身份、通过/失败/未测、各轮producer差异见[后续验收](../../docs/development/2026-11-02-durable-retry-acceptance.md)。原件和只读SQL见[验收资料](../acceptance/20261102/README.md)。未清库/删卷/上传GitHub或修改秋招材料。两份旧失败的正常重解析及新的管理员页面入口见[重新解析后续验收](../../docs/development/2026-11-03-legacy-reparse-acceptance.md)。

## 9aee本地发布（历史快照，旧次数规则）

当前地址 `http://127.0.0.1:18481`，不可变 JAR `9aeeafd40ebce03d840c08c469d04bf034176baecde80060c4160c4c142e2b83`，PID 74157，22:12:11 +08:00 正常启动。完整 `clean verify` 382 项：381 通过、1 个未启用的真实模型 smoke 跳过、0 失败 / 错误；249 项后端源码清单前后一致，最后编译、入库、查询均零在途。

本批实改编译 MinIO / Milvus / JDBC 剩余期限，固定候选 FULL_CANDIDATE_V2 七维协议、解码前原 epoch 来源权限审计、下游准备先读取 PG 元数据再固定输入，以及 Summary 同输入族最多五次、长任务键有界规范 Hash 和正常 checkpoint 重试恢复。最终原生 JAR 的真实 PG 配额13项、半发布恢复9项、审计权限9项通过；真实越额叶隔离 PG / MinIO / Milvus8项、新 Summary读回12项、实际HTTP审计权限4项通过。组件合成模型结果不算真实模型闭环。

真实 CUA 已完成三份关联资料上传、自然语言限定来源回答与引用、独立 Summary 首次生成、同一 Concept task 正常 attempt2 重试和原文查看。Summary 由38b57794生产且当前仍ACTIVE/PUBLISHED；Concept由4748e9f0生产，历史真实成功后因旧来源自动关联进入STALE，当前条目列表隐藏，不能称当前仍可用。最后9aee是部署及读回产物，不倒填为此前生产者。

**仍有实际失败和未测：** 固定两页 PDF 14/15后同配置600秒超时；旧合同 Summary FAILED5 不可直接再生成；同输入自动repair曾越额生成叶产物，已用正常STALE/STAGED隔离并保留物理对象/事实/失败/审计；补充协议另有长键失败任务保留。真实Graph/Wiki升阶、正式大集和KB-03部分I/O边界仍未完整验证。完整通过 / 失败 / 未测、任务ID、产物身份、重跑命令和当前关联失效原因见 [最后发布与验收](../../docs/development/2026-11-02-closure.md)。原件和只读SQL见 [验收资料](../acceptance/20261102/README.md)，私有证据在Git忽略的 `.tmp/acceptance-20261102/`。

## 上一批本地发布与验收（2026-10-08，历史记录）

以下只描述当前冻结发布，不把旧报告换成新发布通过数。

| 项目 | 当前事实与边界 |
|---|---|
| 后端 | JDK 17 Jar `c40d8ee831a30eed5960d0856ccad339fa94205038e38184ca1ea71ff0adac83`；原完整验收为 360 通过、1 个真实模型环境未启用而跳过、0 失败 / 错误。10/08 正常恢复并核对原 239 个后端源码字节，未修改或重新冒称完整测试。 |
| 解析服务 | 原 MinerU 3.4.5 bundle `30033d113bf28c066a3fa1eba8c2da664e70f019a0b9de7b7c886cd821fbcb1f`；本地 MPS / 4 CPU / 600 秒及原 manifest 不变。 |
| 真实文本重解析 | 原 168 字节合成 Markdown 经正常所有者页面提交、真实 Qwen 编码和原子发布，新版本 `local-text / 2` 采用完整 V23 pipeline / checkpoint。PG / MinIO / Milvus / Redis 独立读回 23 项、权限和原任务并发复用 7 项通过；PENDING 期间旧 ACTIVE / authority / 原文块保留，成功后才切换。旧 FAILED5 和未知 pipeline 行保留。 |
| 前端 | 真实使用发现发布后旧详情误报 403，及迟到列表 / 详情覆盖当前选择。按 Preact 请求作用域统一处理筛选、刷新、关闭、切换及卸载；4 项延迟组件先 RED，再与原交互回归合计 18 通过。通过正常 lint / 类型检查 / 生产构建入口发布，51 份源码、24 份构建文件与 HTTP 入口绑定；真实页面读取当前 v2 内容和关闭详情。首页“最近处理记录”显示实际任务类型，原重解析后的关联识别不能算新知识产物生成通过。夹具 API 明确为合成，不代替后台验收。 |
| 真实 PDF | 同一两页原件、155311 字节、原 Gold 在当前 bundle 的 600 秒上限自然 `PARSER_STAGE_TIMEOUT`，观察 601.562 秒。15 个内容检查、11 个视图检查均未到达；仅自然资源释放 / 原记录保留 7 项通过。旧 bundle ad469 的 15 项内容通过不能转用于当前发布。 |
| 仍待实际验证 | 新 Raw 正常上传闭环、当前 PDF 内容和视图、新 Summary / Concept / Graph / Wiki 生成及相应实际故障恢复；完整正式问答与等时调度仍按原固定条件验收。 |

当前操作与独立读回见 [Raw 解析版本与检查点](../../docs/development/raw-parser-checkpoints.md)。通用只读 SQL 为 `scripts/sql/raw-checkpoint-readback.sql`；实际命令、输入原件、SQL、状态观察、旧 FAIL / 新 GREEN、截图与冻结身份保存在本次任务私有工作目录 `work/resume-20261008/evokb/`。实际模型尚未执行的流程不记为通过。

## 原批次结构与页面收尾（2026-10-03 14:37 UTC，历史记录）

最终后端完整验证 full19：288 项，287 通过、1 个真实模型 smoke 显式跳过、0 失败/错误，源码前后一致。不可变 JAR `4a2eeb3ecbb4e1de98b2678bd23aeeac998438cfbf5e260a59acfbbbd1c279ab` 在查询、入库、编译在途任务均为零时正常部署。部署后实际 HTTP/PG 权限与查询范围交叉检查 8/8 通过，无新模型调用或业务改库。

前端按领域拆分 API、统一导航和原生 dialog，完成十三个入口的真实桌面与 390×844 窄屏检查：一处主内容区、无页面横向溢出；上传、历史文本及移动导航的焦点、Esc、权限选项均实际检查。页面验收只读既有数据，不把浏览、原有回答或弹窗打开记为新的模型闭环。代码与默认部署边界见 [后端结构](backend-structure.md)、[默认部署](../default-deployment.md)、[前端结构与验收](frontend-workspace.md)。下方早期模型失败和未测范围仍有效，未改为通过。

## 原批次首次使用与发布回归（2026-10-04 00:52，Asia/Shanghai，历史记录）

GitHub Ubuntu 上的 Java、前端、隐私扫描、Docker 构建及全新 PostgreSQL/Milvus 启动已经实际通过。原首次登录测试仍使用旧页面标题而失败，原日志保留；修订后完整浏览器流程已在本机真实 Chrome 通过：空账户初始化管理员、十三个页面及当前导航、上传入口与原生弹窗、390 窄屏菜单、退出、重新登录和身份读回。没有 Mock 路由，也没有调用模型。

这次本机测试新建独立 PostgreSQL 数据库、MinIO 桶、Milvus Collection 和 Redis 命名空间，复用已有真实依赖服务；16 项原生资源读回通过。它是全新应用状态验收，不能称为第二次全新 Docker 基础设施部署。失败的首轮、SQL、截图和新轮报告均保留，测试进程已正常停止，资源未删除。详细流程及 CI 诊断见 [首次使用回归](../../docs/development/browser-release-smoke.md)。

正常前端第七次发布的 23 个 HTTP 资源逐个匹配上述 Chrome 测试资产，发布前后后端进程身份及 JAR 保持一致。新的云端完整 smoke 仍待新提交执行；此处不预先记为通过。模型质量、正式大集及下方失败/未测范围仍暂停。

## 原批次实施与验收状态（2026-10-03 13:22 UTC 快照，历史记录）

该历史批次后端为 `05dad4a45f0d7030edcaa1bb48b8a2b217de1e88aafb65e23dce6a94bf674d15`，13:15:58 UTC 在全部在途任务自然结束后正常启动。完整构建 full17 实际 283 项：282 通过、1 个未启用的真实模型测试跳过、0 失败/错误，源码清单前后一致。生产 Raw 已接入实际原子编码身份、拒绝未知旧缓存及同任务混用认证缓存/未认证编码；HTTP 使用 Spring Retry 的最多五次和同一总预算指数退避，异步 JDK 响应体完成也受原期限控制。source17 修复其他用户查询会话被错误分类为400的问题，部署后实际 HTTP/PG 为8/8通过，正常拒绝为403。保留 full15 的完整句切块、CSV CRLF/BOM 原文定位和 V20 初始密级，raw-structure-v3 的600/900/80/150参数未变。前端第三次构建的实际页面验证保存密级30、键盘选择及窄屏资料详情；查询历史/重试恢复四项原始范围，用户不填JSON。所有旧失败、源文件、报告和部署产物保留。启动和健康检查不等于功能全部验收。

| 编号 | 已落地与已核验 | 仍需分别验收 |
|---|---|---|
| KB-01 / KB-02 | MinerU 3.4.5 独立服务、真实单页 PDF / Office、冻结本地 tokenizer、编码缓存、Raw Stream / PEL / outbox、600 秒 owner/epoch 栅栏与原生进程崩溃/接管、实际 multipart 合并后崩溃恢复；Stream 30/30 原任务续跑和索引核验通过；修复完整分号条件与 CSV CRLF 原文定位后，该历史批次的 3286 不可变产物的冻结 40×9 切块检查为 360/360 通过 | 同一完整双页扫描及跨页表格在 CPU/MPS 三轮实际解析仍失败；360 项为解析/切块组件证据，不代表模型答案质量或参数最优；24 小时上传过期 / 小时清理未实际等时验收 |
| KB-03 | 完成下载账本与独立条目贡献、稳定资产 ID、跨原合同/补充协议的真实 Luna 关联及 Terra Summary/Concept 发布、全部输入权限、日配额组件并发、普通条目/原文浏览 | 稳定 ID 的真实 Wiki 升阶、实际北京时间午夜新热度调度、达到五次后隔离的 Graph 恢复尚未通过；完整存储域隔离不以调度并发组件代替 |
| KB-04 | 八轮持久预算、真实依赖故障的 FAILED 状态、共享的 8 段 / 6000 token 上限及实际转交来源编号；71e2 context 版本上的真实自然语言恢复回答、引用、ZIP/原件和独立贡献核验通过；该历史批次的 3286 的 Hash40 原生矩阵为 39 通过、1 原冻结夹具无效，另有独立开发 ERRATA 的 LF→CRLF→重复版本/缓存复用通过 | 正式 240 主集、两方案各 65 次复杂留出复测、100 开发文档的真实模型 Hash 收益实验未全部执行；100×5 的实际解析/切块/token 输入计划已核验，尚未调用模型，不是耗时收益；原 Hash40 无效夹具不改 Gold、不记通过 |

这些行是范围状态，**没有将任一完整 KB 编号标记为全部完成**。正式合成资料清单包含 40 原文、140 问答，固定 Hash 后再导入。历史3854403e部署上的只读交叉审计40份原件为：38 份原始入库及权限读回通过、2 份真实模型入库失败、0 份未启动；这不是 Summary、Wiki、Graph 或正式问答通过数。旧上传 DTO 静默忽略初始密级，F01/F02 财务页曾误用默认 10；09:45 UTC 已通过正常 ADMIN PATCH 恢复为预定 30/ACL2，原文件 Hash 和其它资料保留。首轮详情由 200 变为错误的 400，`formal-source-classification-normal-repair-1` 原失败保留；当前部署的 HTTP 28 项复测中，两份财务页管理员 200、普通账号 403，PG/MinIO/Milvus 原始投影校验一致。新真实页面上传的密级 30 原件正常入库，普通账号查不到；低密级管理员的越权上传在创建文件或任务前即 403，页面过高选项禁用，未提高既有账户权限。F03 财务页和 F04 合同各保留五次失败隔离记录，隔离任务不隐式重置。最终新审计为 `raw-formal-current-deploy10-audit-3.json`，本轮新增 27 份原件完成正常 staged API、密级读回、任务及 MinIO/Milvus 核验；其中连续剩余 26 份全部首次尝试完成。材料、脚本或启动存在不计成正式题目通过。

历史3854403e部署上的独立自然语言质保查询 `048b0d36-5a3e-47ec-b482-cc40b08e1a31`、单方邮件与补充协议查询 `bd8f8aed-ff50-488a-9754-22efddc318b9`、缺发票能否要求付款查询 `069d7f04-eb92-47c8-ab79-524a385f79a0` 均真实 ANSWERED。每轮各 13 项持久预算、8 项当前引用、16 项实际 CUA ZIP 原件及下载回执校验通过，截图、AX 文本与只读 SQL 保存在 `work/resume-20261003`。这些是开发闭环，不能计入尚未执行的正式 140 题。三次真实 ZIP 下载后，事件条目 `0c41e176-4717-943d-b943-4ef1c259e529` 的独立有效贡献实际为 6，达到 Wiki 候选阈值；原合同条目自身仍为 3，不广播事件热度。下一次正常午夜调度与真实 Wiki 发布尚未验收。另一次真实事实核验诊断仍有 1 个主体颠倒的改写被 Luna 误判支持；提示约束增强未解决该质量问题，不能把所有已有“模型核验通过”的摘要当作事实质量已通过。

固定两份开发文档的真实 Hash 小对照已完成：24 个来源执行案例全部通过，236 项来源断言；全量方案编码 36 条 / 3964 token，Hash 方案编码 21 条 / 2178 token。端到端分别 452.605 秒与 451.768 秒，不能声称显著提速。实验自身另外有一次真实身份预检编码，计入总成本；100 份开发文档大对照尚未执行完。实际编码身份包含原始 revision、tokenizer、完整输入与输出向量 Hash、执行 pipeline、包版本和 CPU 配置，生产 Raw 的原子响应契约已正常部署，但新部署上的正常双版本认证复用模型闭环尚未执行；旧缓存保留且不会追补身份，不能把历史实验验证冒称新生产流程已验收。

知识演化的独立原生 PostgreSQL 矩阵实际 30/30 案例通过（19 开发、11 留出；67 项断言）。每例独立保留 schema、种子 SQL、任务、下载贡献和候选账本，覆盖 3/5 门槛、失败传输/事务回滚、独立条目热度、权限/版本失效和并发日 20/10/10 配额。查询/摘要/概念载荷为明确的合成组件夹具，未调用模型，也没有修改真实业务热度；它不能替代真实生成内容质量、实际午夜、30 天等时或浏览器传输验收。复跑及已实际执行的手动只读 SQL 见 [知识演化机制](evolution-mechanisms.md)，冻结 Gold SHA 为 `025625143fa5572dc52256bf8474ea6c6f1708780e8eff09eae9f2fff392506b`。

随后完成的正式 baseline 开发三题为 2 通过、1 失败：合同金额题与原合同/补充协议条件变更题均达到固定事实及原始证据要求；受限财务题在前一题重排 kernel 尚未释放时遇到三次 embedding 503，最终 FAILED。数据库输入和结果未包含越权资料，但依赖失败不能算权限题业务通过。原失败与日志保存在 `formal-baseline-development-three-1`；有界指数退避已部署；实际 CUA 保留原问题/四版本范围重试为新会话938a2027-e434-4c4d-87dd-4d97bf299fac，13:06:27～13:12:24仍FAILED。13项范围、持久预算、授权、引用和零下载热度核验通过，但固定预期安全终态失败，不能把它算正式题通过。原因是重排客户端30秒观察结束后实际kernel仍占用资源，随后embedding的五次退避在120秒总预算内仍超时。原先2通过/1失败报告和本次新失败分别保留，源17权限修复也没有伪造模型恢复。其它137道正式问答未在本批启动。

证据和复跑入口见 [暂存发布与稳定条目](staged-publication.md)、[Raw / multipart](raw-task-and-multipart.md)、[条目浏览](knowledge-entry-browsing.md)、[查询上下文与故障分类](query-evidence-context.md)。本轮原始报告、只读 SQL、失败和脱敏截图位于本任务 Codex 工作目录的 `work/resume-20261003/`。下方历史报告保留实际发生时的产物，不冒充当前重新执行。

依赖正常恢复使用现存容器，不删除数据卷：

```sh
docker start evokb-target-milvus-1
python3 scripts/deploy-target.py check --verification .tmp/target/backend-verify-manual-01.json
```

部署脚本先核验已有 PG、Redis、etcd、Milvus、MinIO 健康；失败时不启动新 Java 进程。目标四个依赖配置 `restart: unless-stopped`，当前容器已用 `docker update --restart unless-stopped` 同步，不重建镜像或数据卷。当前仅验证实际手动恢复与策略读回，未来自动重启事件未人为触发。

## 地址与数据边界

| 服务 | 本地地址 | 边界 |
|---|---|---|
| 前后端 | http://127.0.0.1:18481/enterprise/ask | 新环境；独立 `ekb_target_session` Cookie |
| PostgreSQL | 127.0.0.1:15465 / evokb_target | 独立数据卷，Flyway 管理结构 |
| Milvus | 127.0.0.1:19535 | 独立数据卷、etcd、对象桶 |
| Milvus 健康检查 | http://127.0.0.1:19095/healthz | 健康检查不代表业务验收通过 |
| Redis | 127.0.0.1:16385 | 独立数据卷，AOF |
| MinIO | http://127.0.0.1:19400 | 复用已授权本地实例；业务桶 `evokb-target`、Milvus 桶 `evokb-milvus-target` |
| Embedding / Reranker | http://127.0.0.1:8110 | 已有 `embedding-model` 容器 |
| 旧环境 | http://127.0.0.1:18480 | 不共享数据库、资料桶或登录 Cookie |

所有主机端口仅绑定 loopback。脚本不清空数据库、不删除卷、不覆盖已有文档、不更换模型，也不执行数据库任务状态造假。配置和账号保存在被 Git 忽略的 `.env.target`、`.tmp/target/accounts.json`，权限为 0600。

## 初始化与启动

在仓库根目录执行。需要 JDK 17、Maven、Node/npm、Python 3 和 Docker。当前使用既有 `evokb-acceptance-minio-1` 及 `evokb-acceptance_default` 网络，启动前确认其存在；请勿执行旧环境的 `down -v`。

```sh
python3 scripts/deploy-target.py init --from-env .env.acceptance
python3 scripts/deploy-target.py deps
python3 scripts/deploy-target.py build-web --log .tmp/target/web-build-manual-01.log
```

`init` 只从已有私密配置复制获准的模型及 MinIO 接入，新建隔离数据库凭据。已有 `.env.target` 时原样保留；有目标数据库却丢失私密配置时拒绝生成替代凭据，应恢复原配置。已有配置的保留分支已实测，新建配置分支尚未在空环境重跑。

后端必须通过完整构建，再生成与源码、JAR、测试结果绑定的验证记录。每次使用新日志、报告路径，保留失败尝试。

```sh
python3 scripts/verify-backend.py --output .tmp/target/backend-verify-manual-01.json
python3 scripts/deploy-target.py check --verification .tmp/target/backend-verify-manual-01.json
python3 scripts/deploy-target.py start --verification .tmp/target/backend-verify-manual-01.json
python3 scripts/deploy-target.py status
python3 scripts/seed-acceptance-accounts.py --environment target --report .tmp/target/account-seed-manual-01.json
python3 scripts/seed-target-business-data.py --output .tmp/target/business-seed-manual-01.json
```

`start` 核对完整后端源码清单及校验值，只部署验证对应的不可变 JAR，拒绝端口占用或已有受管进程。当前 public 通过正常 Flyway 应用至 V23（V1～V23 已应用），后续必须新增迁移文件，不能修改已应用文件。页面资产先发布所有带 Hash 的文件，最后原子替换 index，保留旧资产供已打开页面使用。

停止、重启均保留数据。停止只对记录的 PID 及进程出生时间匹配的进程发送 SIGTERM：

```sh
python3 scripts/deploy-target.py stop
python3 scripts/deploy-target.py start --verification .tmp/target/backend-verify-manual-01.json
```

## 可手动使用的关联数据

`deploy/acceptance/target-fixtures/` 中三份资料均明确标记为虚构验收数据：原合同、独立补充协议、无关仓储规程。补充协议把尾款期限从 30 日改为 15 日，合法发票前提保持；900 万元总价、72 小时试运行、24 个月质保保持。

种子脚本先只读核对同名文档的实际 Hash 和状态，冲突即拒绝全部上传。正常 API 上传相同内容时复用已激活版本。已实测三份全部复用，导入前后均为三个文档、三个版本，原始元数据保持。

页面登录后可从“资料管理”上传或查看资料，再提问：

> 星桥 HT-MV-017 合同现在验收通过后几天支付尾款？请结合原合同和补充协议，说明发票条件、合同总价以及质保期，并给出原文依据。

该自然语言问题已通过真实浏览器提交、真实模型回答，实际返回五条引用，均来自两份合同；点击引用能查看来源，正向反馈已保存。最终部署后重新通过 CUA 提交同一自然语言问题，新查询 `3602ec1b-bf15-434e-8f00-369f1c22badb` 也真实执行、正确回答并保存正向反馈；不是旧回答的页面回放。

Markdown 正文使用独立的 Marked 实例和 CJK 扩展解析，再通过 DOMPurify 限制为格式标签。该扩展处理中文标点紧贴强调标记的通用问题，保留原始回答及代码里的字面符号。引用及下载动作使用独立的登录态控件。实现依据：[Marked 文档](https://marked.js.org/)、[CJK 扩展](https://github.com/tats-u/markdown-cjk-friendly)、[DOMPurify](https://github.com/cure53/DOMPurify)。

## 测试和只读 SQL

报告输出必须使用新的路径。组件脚本创建独立 PostgreSQL schema / Milvus database 并保留 SQL 与资源，不修改 public 工作流数据。

```sh
python3 scripts/test-milvus-components.py --output .tmp/target/milvus-manual-01.json
python3 scripts/test-postgres-graph.py --output .tmp/target/graph-manual-01.json
python3 scripts/test-acceptance-concurrent-login.py --environment target --output .tmp/target/login-manual-01.json
python3 scripts/test-target-session-isolation.py --output .tmp/target/cookie-manual-01.json
python3 scripts/test-target-runtime.py --batch <真实批次ID> --output .tmp/target/runtime-manual-01.json
python3 scripts/test-target-api.py --session <真实查询ID> --batch <真实批次ID> --output .tmp/target/query-manual-01.json
docker exec -i evokb-target-postgres-1 psql -X -v ON_ERROR_STOP=1 -U evokb_target -d evokb_target < deploy/acceptance/target-runtime-inspection.sql
```

已测批次：`aad75309-8d57-4cd6-89b1-76ff1be8f345`；已测查询：`4dabb107-f842-45dc-ae1e-4ef0cca88994`。组件 SQL、失败记录、资源校验、脱敏截图保存在本次交付目录 `work/evokb-implementation-20261002`（位于 Codex 工作目录），由该目录的 Milvus 批次报告索引。

## 历史批次结果（2026-10-02，不能当作当前未完成清单）

| 状态 | 范围 | 结果 / 限制 |
|---|---|---|
| 通过 | 完整 Maven 回归 | 178 项：177 通过、1 跳过、0 失败 / 错误；跳过不计通过 |
| 通过 | 真实 Milvus + PG + Qwen | 最终 28 项；1024 维、中文 BM25、单 Collection、检索前权限过滤、ALL 来源失效、稳定 Concept/Wiki ID、重复发布、完整来源元数据、拒绝不兼容分词配置而保留原 Collection |
| 通过 | 真实 PostgreSQL 图谱 | 15 项；实际检索种子、权限在种子 LIMIT 前、两跳、每节点 / 总边上限、方向、去重、环路、不可变修订 |
| 通过 | 浏览器入库与资源核对 | 24 项；三个正常任务、数据库来源块、实际对象字节 Hash、11 个 Milvus RAW 分片；上传不自动编译派生资产 |
| 通过 | 真实问答及资料包 | 25 项；实际模型答案及引用；API 与 CUA 均实际完成 ZIP 下载并核验原件字节。另通过原文预览、中文文件名原件下载及撤权验证，详见权限批次记录 |
| 通过 | 并发登录 / 双环境会话隔离 | 9 个并发请求成功；8 项会话隔离校验；不代表全部业务并发故障矩阵完成 |
| 通过 | 浏览器 Markdown 组件 | 最终 16 项；加粗、列表、表格、CJK 标点与相邻正文、英文 / 代码 / 转义保持、不可信 HTML 消毒。另核对实际新答案渲染，正文与引用未改写 |
| 失败后已修复 | 入库统计 | PG 不支持 SUM(boolean)，使任务停在 RUNNING；改 COUNT FILTER 后正常重启续处理，三个任务完成且没有新增文档版本。保留复现 SQL |
| 失败后已修复 | Milvus JSON 元数据 | SDK 返回 Gson JsonObject，旧解码只接受 Map，导致标题路径和来源块 ID 丢失；先增加断言复现失败，再修复并通过真实组件测试 |
| 失败后已修复 | CJK Markdown | 新模型回答使用中文标点紧贴加粗标记，普通 CommonMark 解析保留了部分星号；保留真实失败截图，接入现有解析器扩展，16 项真实组件检查及实际答案渲染通过 |
| 失败后已修复 | CUA 下载事件 | 早期签名链接与新窗口未捕获完整文件；改用带登录态的应用下载入口和原生下载动作，最终实际 ZIP 与中文文件名原件均完成并核验字节 |
| 未测 | 新环境从零创建 | 依赖实际启动及现有配置保留已测，空环境 init 分支与重新创建全部数据卷未测 |
| 未完成 / 未测 | MinerU 3.4.5 | 常驻解析服务尚未部署；Markdown 使用 local-text，不能算扫描 PDF / 表格解析通过 |
| 未完成 | KB-01 / KB-02 | 600 秒固定 deadline、epoch / owner / 来源 / ACL 发布栅栏、Stream + Redisson、投递缺口与 PEL 恢复、终态 ACK 尚未接入全链路 |
| 未完成 | KB-03 | 已接入当前权限校验及真实下载后的来源贡献；PG 资产来源目录、条目展开与贡献、夜间配额、自动沉淀、Graph 编译的 scope_key 与新版图谱 UI 待完成。后台发布栅栏与完整安全故障矩阵尚未全链路完成 |
| 未完成 | KB-04 | 本批已完成检索前持久化的八轮预算及并发/崩溃组件验证；实际 tokenizer 的 600/900/80 分块、原生 multipart、240 问及 12 上传 / 12 图谱故障验收未完成 |

本批属于 KB-01、KB-03、KB-04 的部分实施和验证，未把任何完整任务编号标记为完成。不要把源码中的旧轮询 worker、旧命中计数、旧资料包签名链接视为新版 Stream、成功下载回执或原生 multipart 的验收。

账号 / 权限 / MCP 批次的具体代码、可重跑命令、人工测试方法和状态边界见 [权限与下载验收](permissions.md)。该历史批次部署 JAR：`f81b08c38cafb9e349b6f40da742bef5d1a036890e9df17c6df5d89d425a32db`。旧报告保持原样，不能把它们的构建版本混称为最终版本。

八轮预算批次的代码、并发/崩溃实测、升级数据保留及新浏览器模型查询见 [检索预算验收](retrieval-budget.md)，该批终态验收 JAR 为 `5badf898c24ce766ffa3a53173c0bd4680ceec35a16d4e6537a4dba0ae3f40bd`。

来源下载贡献批次的代码、真实浏览器 ZIP、并发回执和保留 SQL 见 [回答资料包的实际下载贡献](download-usage.md)。条目来源目录、实际展开和独立下载贡献见 [条目参与及下载归属](asset-attribution.md)，后续关系生成与发布修复见 [业务范围与一致图谱发布](graph-publication.md)。该历史批次部署 JAR 为 `3331afb943607a99f36f44cc347d2648ccd76e39773bb114399d62970b4a8b50`；`bd0747f7…` 和 `a63bfb13…` 为历史部署，Graph 第三次实际模型执行属于 `2a27cb92…`，各报告保持真实执行版本。
