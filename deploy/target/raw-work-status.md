# Raw专题：实施与验收状态

2026-10-03按用户新要求暂停剩余功能与大规模正式验收，转入页面、结构和仓库整理。没有以未测结果填补通过项；不取消在途任务、不改业务终态或清数据。以下是分批证据，不是一个构建上的统一全通过声明。

| 项目 | 已完成证据 | 失败/未完成边界 |
| --- | --- | --- |
| 原件解析与结构切块 | 本机实际MinerU3.4.5 Office及单页PDF；6资产tokenizer与服务计数一致；历史328635cb JAR原Gold离线40×9矩阵360/360 | 完整双页扫描Gold在CPU2/CPU4/MPS三轮真实600秒失败，不能拆单页补算；360不是PDF或模型质量矩阵 |
| Raw Stream/恢复 | 历史3e981429绑定30/30独立案例，20开发10留出；原事故后续原任务接管，fixture向量明确 | 不算真实模型或Hash收益；PG/Milvus非计划故障记录与计划案例分开 |
| 上传与初始分类 | 正常分片/续传、原件校验、初始等级30真实页面/API及PG/Milvus读回；旧非法/过低权限拒绝保留 | 各批独立产物；没有扩大权限或重置旧任务 |
| Raw原子编码身份 | 严格认证key、未知不缓存、跨批认证/未知不混用；Spring Retry与完整HTTP正文观察期限；48/48定向测试、0模型；完整回归后正常部署 | 新生产普通原件两版本的实际认证缓存验收尚未执行，旧三参probe/旧向量不得补stamp或当成此通过 |
| Hash主40 | 39通过、1INVALID_FIXTURE，开发24/24留出15/16；额外格式字节变化补证独立1通过 | 原件相同却期待新版本的夹具问题保留，不修改Gold或主分母 |
| Hash真实小对照 | 历史3854403e JAR两份预选DEV资料24/24来源步骤，57有效编码输入，额外预检成本单列；全量452.605秒、Hash451.768秒 | 未观察到显著加速，不算百份正式对照或问答质量 |
| Hash100可恢复编排 | 同385 JAR的100份/600原件输入计划、20×5组冻结；fixture暂停/续跑12/12及一完整5份组60/60；另独立JVM exit73后同file真实600秒恢复12/12，13次执行/12项谱系读回 | 正式100实际模型编码未执行；当前real-plan-1仅PREPARED_NOT_EXECUTED，脚本已冻结；fixture0模型不算收益 |

最新运行中产物以 `.tmp/target/process.json` 为准，历史报告保留各自实际SHA。原子身份批完整full16为283项、282通过、1显式模型skip，正常部署e9d63bbc；随后full17只收尾会话权限响应，正常部署05dad4a4。这两次构建通过均不代替尚未执行的普通Raw原件/缓存认证验收。

普通 `RawIngestionApplicationService` 的恢复证据是已有原件/RawBlock、任务owner/epoch/期限以及受控发表指针；**不会写 `embedding_rebuild_checkpoint`**。该表及`raw-v3-atomic`strategy用于独立 `RawEmbeddingRebuildService`。准备新生产两版本验收时已确认此边界，只做了源码/接口审计，尚未编写或执行新原件脚本；不能把普通Raw表为空说成rebuild checkpoint已测。

原子身份的历史39/42/47/48定向snapshot及慢正文原FAIL/修后typed退出均保留。最新Hash脚本原成本字段只计续跑进程的旧报告未覆写；新helper包含初次尝试及停机等待、保留每次调用成本。正式计划、fixture报告和真实小对照各列分母。

证据保存在当前任务`work/resume-20261003/`，关键前缀：`raw-atomic-junit-*`、`raw-http-slow-body-*`、`raw-chunk-40-*`、`raw-hash-40-native-1.*`、`raw-hash-efficiency-real-subset-1.*`、`raw-hash-resume-crash-*`、`raw-hash-resume-crash-lineage-*`、`raw-hash-resume-final-fixture-*`、`raw-hash-100-resumable-plan-3/`及`raw-hash-100-real-plan-1/`。

复跑入口见[Raw编码](raw-encoding.md)、[原件/Stream/multipart](raw-task-and-multipart.md)、[切块与Hash](chunk-and-hash-experiments.md)和[Hash续跑说明](hash100-resume-runbook.md)。恢复正式模型实验前应重新协调共享服务窗口并核对原冻结身份，不能静默换JAR或模型。
