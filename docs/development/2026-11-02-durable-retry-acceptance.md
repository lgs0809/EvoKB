# 后台持续退避后续验收

本记录对应用户在2026-11-02晚明确调整“失败后后台持续指数退避”的后续批次，延续到11月3日。它补充而不重写 [9aee历史发布](2026-11-02-closure.md)。模型仍为 gpt-5.6-luna / gpt-5.6-terra，Qwen3-VL-Embedding-2B请求1024维及原reranker；PG / Milvus / MinIO / Redis 使用原隔离资源。

## 本批改动

- Generation和Raw复用 `DurableRetryBackoff`：持久化nextAttempt，10秒指数增长至15分钟，上界80%～100%的稳定抖动，失败/attempt/epoch不清零。等待不占模型线程，重启恢复不重抽延迟；原600秒单阶段、有限HTTP重试、owner/epoch与当前完整输入权限未放松。
- 同实际Summary输入的普通/repair/管理员command复用一条正常链，原别名历史保留；继承最晚nextAttempt及成员最大priority，沿用repair100，不手动改库抢slot。无效/撤权/被替代输入仍拒绝；旧Raw缺解析身份仍冻结。
- 重试API及页面不再声称五次永久停止，展示累计次数、最早自动重试时间；旧终态失败仍留存。
- 下游入队故障后复用已审计的ACTIVE Summary检查点。同文新生成以原task/epoch为不可变身份，不覆盖旧STALE产物。
- V24独立保存scope_fact_ids；描述fact_ids及审计语义不变，投影/权限使用两用途的实际证据并集。旧行默认[]、不伪造历史证明，页面提示部分历史来源尚缺专用范围证明。详见 [设计及重跑命令](durable-background-retry.md)。

## 新旧生产者分别记录

| 执行/资产 | 实际结果和生产者 |
|---|---|
| ce583持久退避发布 | `ce583183c7105f72bc8b1d32ea552baf4d7f51baf8a1a8c86f64a055c8b1ced3`；23:21:22 +08:00正常启动，384项中383通过/1环境smoke跳过/0失败。 |
| 原11/2合同Summary | 原task `5c9895d2-c7a2-485a-9025-6343e5f967a4`，前五条失败全部保留；正常恢复后attempt6于23:47:47运行、23:51:30成功，单次223.8秒，小于600秒；不是换subject凑新次数。ce583为实际producer。 |
| 原合同Summary物理读回 | `contract-restored-summary-readback-1.json` 12/12：原件/MinIO哈希、FULL_CANDIDATE_V2七维、当前ACL/冻结输入、epoch6对象命名、PG PUBLISHED、实际Milvus内容/证据/1024有限归一向量、原epoch响应审计与完整输出Hash。旧异常Summary单独保留为STALE/STAGED。 |
| 原合同后续Concept | `530a0b3f-686d-4369-a272-cece5c66a7ce` 正常attempt1在ce583成功；6个当前条目ACTIVE（旧5条更新修订及新条目），关联pending正常associated。管理员/普通成员API及原文/PG12项读回通过；它在V24部署前生产，不能冒称已保存专用scope证明。 |
| 10/7旧Summary | 原task `605015db-5ce9-4f06-9d99-556518e794b0`，attempt6严格SCOPE引文拒绝后持久退避，attempt7自然成功；保留attempt6失败与nextAttempt，不把拒绝说成宕机。尚未为该源另做完整物理质量读回。 |
| 补充协议检查点 | `6639b354-aef6-49f6-933b-dfa1dc5275de` 同task attempt2正常成功，复用原4748的ACTIVE Summary，不重新提取/审计/编码。ce583不是其Summary producer。 |
| 新独立说明 | 原38b Summary仍为其原producer；原4748 Concept历史成功不更换producer。新ce583后续来源关联/修订与旧记录分开。 |

最终scope新发布及最后状态见下节；未列物理通过的Graph/Wiki不自动算通过。完整旧五次模型输出当时未留存，仍不可恢复；新版本才有受权限限制的64KiB审计文本及完整响应Hash，不倒填旧响应。

## 真实组件与页面门禁

| 实际命令/报告 | 结果与边界 |
|---|---|
| verify-backend.py --skip-format，backend-verify-9.json | clean verify 384项：383通过、1未启用真实模型smoke跳过、0失败/错误；源码前后稳定；JAR fa1ec37a0d820a6de14d550182a5c101b5f3bb04429ef2da63ea5bbdbe929680。 |
| test-postgres-durable-retry.py，scope-priority-native-3-final.json | 新原生JAR、真实PG70/70：24并发普通/repair入队、18并发Rawclaim、两类8次失败/第9次过期、持久nextAttempt/抖动/上界、重启、旧链优先级继承、ACL/源失效、600秒不续约。 |
| test-postgres-entry-scope-proof.py，scope-native-4-final.json | 新原生JAR、真实PG11/11：V23升级默认[]，描述/范围不同事实和原文，正常owned发布、投影包含scope-only证据、API分开展示，仅范围证据撤销即禁列表/详情，同源ACL变更拒绝。 |
| test-postgres-summary-handoff.py，scope-handoff-native-1-final.json | 新原生JAR、真实PG12/12：实际下游INSERT故障、原任务失败保留，正常等待退避后同task attempt2复用检查点，长键有界规范Hash，同文新生成不覆盖旧STALE。 |
| test-postgres-compilation-command.py，scope-command-native-1-final.json | 新原生JAR、真实PG20/20：管理员准入、完整输入冻结、同链重试/command并发及权限。 |
| npm lint / typecheck / 正常build-web | 成功，web源码与资产Hash在scope-web-manifest-1.json；先复制hashed资源后更新入口，旧资源保留。 |
| Chrome组件 entry-scope.spec.ts + task-failure.spec.ts | 10/10：独立范围原文入口、旧行缺证明明确提示、第12次继续等待、普通成员诊断边界。明确合成API组件，不是真实生成。 |
| computer use | Chrome真实页面已核对原5c第6次成功，保存browser-retry-six-success-ce583.png；最终新JAR页面另存，不混用版本。 |

探针均保留独立PG schema和对应seed.sql/runtime日志。最初scope-native-1夹具FK、scope-native-2与priority-native-1旧infra overlay失败保留；修正为完整源码编译overlay后11/70通过，最后又用完整验证新JAR原生复测。一次在V23 reader提前运行V24专用读回的错误保留contract-restored-concept-readback-1，不充当事实质量失败或新版本通过。无数据库清空、状态造成功或卷删除。

## Raw身份拒绝及未关闭事项

两条10月3日旧Raw：047424ea…财务登记、af87c189…采购合同，原有6/10个raw块，冻结parser_pipeline_sha256、parse_checkpoint_json与claimed身份均NULL。正常attempt6在模型前以PARSER_IDENTITY_CHANGED拒绝：无法认证旧身份，并非次数用尽，也不能据错误码推断换过模型。实际9aee和ce583解析及依赖13class字节一致，两JAR独立运行计算的完整当前pipeline同为5e1b8b13533c0fbaf792bb40f37b3e13d544ef2ad41c2b525c9630ef0a76a369；此两例是local-text2，不经过MinerU/MPS。未自动批量迁移未知legacy Raw。

- 原固定两页PDF仍实际失败：原件/Gold不变，一轮14/15（atceptance），另一轮原600秒超时603.354秒；视图未到达。不算模型凭据受阻，也没有按单词特判。
- 正式完整质量题集、真实Wiki升阶、实际午夜/长时间调度、100文档Hash收益及KB-03尚未覆盖的pool/networkpartition/外层事务/iterator关闭/Raw I/O边界未测或未完全关闭。
- 原4个Graph严格coverage拒绝留记录；后台持续退避不保证模型质量自动达标。已观察有限队列中原低优先级Summary最终开始并成功，没有据此宣称任意高优先级洪峰的普遍公平性已证明。
- 旧9aee隔离的72bf…异常Summary仍STALE/STAGED，原失败/事实/对象/物理向量保留，不能自动冒充本批正常成功。

手工只读核对使用 [durable-retry-readback.sql](../../deploy/acceptance/20261102/durable-retry-readback.sql)。真实输入三份Markdown未修改。所有私有证据在.gitignore覆盖的.tmp/acceptance-20261102/；凭据仍只在0600私有环境/账号文件，未推送GitHub。

## 最终正常发布与独立范围证明读回（2026-11-03 00:18 +08:00）

- 地址：http://127.0.0.1:18481/enterprise/wiki 。最终后端是 `ef29c903d74b4bb541db0f1c8b41f84f95aef053650e8c4d41c4162bc8558b89`，PID93814，00:11:45正常启动；`backend-verify-10.json`仍为384项（383通过、1环境未启用smoke跳过、0失败/错误），252后端文件清单稳定，SHA `cda72b44953181ee513aeb91a6ffd31624b18a2e6c326d6efdbb424123017d4f`。16项范围证明/正常重建与20项command组件已在最终原生JAR通过。前述70项持续退避与12项handoff最初属于fa1ec37a版本，最后又针对实际部署的不可变ef29 JAR重跑，分别保存为scope-rebuild-retry-4-final和scope-rebuild-handoff-2-final；不改标旧报告。
- ce583旧进程在只读观察到compile/raw均0的00:09:14后正常SIGTERM，保留回执scope-idle-stop-1.json；未改库、重置或提前接管owner。新JAR正常Flyway应用V24，不回填旧scope proof。
- 通过既有 `/api/enterprise/compilation/concept-projection` 正常提交 `944991e8-5490-426a-ab5a-69828044bcb1`；普通成员403、管理员202、重复提交返回同task，4/4。第一次后台执行00:13:03→00:14:36 SUCCEEDED，93秒；只从当前完整审计输入重验已存锚点，未重新生成描述。实际5条目变为：尾款期限计算条件/青禾实验室/HT-2026-1102/远川设备公司修订4，质保期修订3。
- `scope-rebuild-physical-readback-2.json` 12/12：实际3原件、3审计Summary、5个投影、完整冻结输入、独立scope事实逐字锚点、真实MinIO完整Hash、Milvus内容/证据并集/1024维实际向量。此任务的producer和reader均ef29；原Summary producer仍ce583/38b/4748各自保留。
- `scope-rebuild-api-readback-1.json` 15/15：实际新revision、描述/别名/业务scope/描述事实/原来源绑定逐字不变；每个重建来源有独立scope事实及同版本可读原文；管理员/普通成员/匿名读权限。`scope-rebuild-history-readback-1.json`3/3：旧revision输入保留，既有audit_event保存5条修复的previousSources（旧[]）及准确前后revision。
- 真实Chrome通过computer use刷新、看到“质保期”修订3；描述原文是24个月，适用范围原文是合同编号。实际点击“查看范围原文1”打开合同编号原文抽屉。截图 `browser-scope-separated-ef29.png`、`browser-scope-original-ef29.png` 属于新JAR，旧ce583第6次成功截图单列。页面此时另有其他历史源正常恢复出的4条，不能当本次5条scope修复产物。
- 第6条“交付与验收安排”后来识别到补充协议与说明两个待关联来源，正常STALE；本次正常projection只处理5个当前ACTIVE条目。该条目的后续完整关联仍未完成，没有直接改ACTIVE或改候选展示规则。原11/2 Summary仍同task SUCCEEDED6，priority100由成员继承；原五条失败前缀Hash `9d561c7dddacce713fd01356756d016966bf7e80ad1f7736744c772562988d7a` 完全未变。补充协议后续自然Summary attempt3、独立说明后续自然attempt2的检查点/历史与原producer分开。
- 后台另一个真实Summary运行期间，认证元数据/条目/任务请求分别48/136/75毫秒返回；这只证明前台HTTP与后台独立响应，不是并发真实问答模型延迟SLO。最后快照无RETRY_WAIT，现有失败的持久nextAttempt与重启恢复由保留历史及真实PG70项验证，不能伪造一个等待任务作展示。

实际命令（使用新输出名，保留每次失败）：

```sh
python3 scripts/deploy-target.py check --verification .tmp/acceptance-20261102/backend-verify-10.json
python3 scripts/deploy-target.py start --verification .tmp/acceptance-20261102/backend-verify-10.json
python3 scripts/submit-target-entry-scope-rebuild.py \
  --summary 80e4f192-6393-444a-aafe-4d23f02923aa \
  --jar-sha256 ef29c903d74b4bb541db0f1c8b41f84f95aef053650e8c4d41c4162bc8558b89 \
  --output .tmp/acceptance-manual/scope-submit-new.json
python3 scripts/observe-target-stable-entry.py \
  --version 75782b74-0580-49af-8ea5-a4e3bad22102 \
  --output .tmp/acceptance-manual/scope-observed-new.json
python3 scripts/test-target-stable-entry-publication.py \
  --version 75782b74-0580-49af-8ea5-a4e3bad22102 \
  --projection-task 944991e8-5490-426a-ab5a-69828044bcb1 \
  --execution-proof .tmp/acceptance-manual/scope-observed-new.json \
  --output .tmp/acceptance-manual/scope-physical-new.json
```

最后读回首次因本机Python3.9不接受PG省略末尾0后的5位微秒而停止在执行身份校验，未跑物理查询。修正为保持同一时刻的零补齐解析后，25项纯选择器回归通过（等时仍拒绝、FAILED不能被旧成功替换），才完成上述12项物理读回；原失败scope-rebuild-physical-readback-1保留。最后只读SQL第一次遗漏docker -i而没有执行任何语句，空日志不算通过；正确命令输出保留durable-retry-readback-final-2.log并实际COMMIT只读事务。未对业务数据作SQL成功改写。

后续：两份旧NULL管线原件已按现有正常重新解析生成v2，保留旧FAILED6；入库记录现可直接发起该正常恢复。完整两份23项物理读回、25项页面组件、实际CUA复用与保留的首轮失败见[11月3日重新解析验收](2026-11-03-legacy-reparse-acceptance.md)。旧快照不改写为新成功。
