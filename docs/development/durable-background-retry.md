# 后台持续退避与同输入重试链

2026-11-02 用户将旧的“累计五次后停止”调整为后台持续指数退避。本规则只改变持久后台任务；用户主请求、单次模型 HTTP 重试及单阶段 600 秒仍有原来的有限预算。

## 执行与等待分开

`DurableRetryBackoff` 供 Generation 和 Raw 共用：第 n 次失败的退避上界为 `min(10秒 × 2^(n-1), 15分钟)`，实际延迟在上界的 80%～100% 之间。抖动由 task/file ID 与累计 attempt 的 Hash 确定，不同任务错开，同一失败恢复不会反复抽取新延迟。长期失败也只增加等待，不增加单次 HTTP / 模型 / 阶段时限。

每次失败保存原历史与累计 attempt / epoch，将下次执行时间写入现有 `next_attempt_at`。Generation 进入 RETRY_WAIT，Raw 回到带 nextAttempt 的 QUEUED；等待时不占用模型工作线程或编译控制权。到期后通过原正常 claim 获得新 owner / epoch 和新的固定阶段期限，不续约上一轮。

Generation 仍由原知识库编译控制限制同库在途数量；Raw 保持既有 Redis Stream / PEL / outbox、投递恢复及有界工作池。当前本地 EvoKB 的入库池配置为1，编译池为1，不因恢复多条旧任务增加线程。查询主流程的有限重试和超时未改。

## 普通、repair、command 不能提前或分叉

同知识库、来源版本、语义 policy 与实际 ACL 的 Summary 固定复用最早的正常任务作为重试链。repair / command 名称不会产生独立失败计数、提前到期或并行模型调用。原历史别名和失败不删除；旧仍在排队的别名停止执行并指向原链，原链继承同输入已经持久化的最晚等待时间和成员最大 priority；沿用原 repair 的100，不创造新优先级，也不通过改库抢执行位置。

管理员 Summary command 仍先校验管理员和全部来源准入。若复用已有普通链，仅返回已有任务，不把另一个 command 身份或权限快照错误写到它上面；已有 command 的提交者及冻结输入不被覆盖。Concept / Graph / Wiki 保留自己的冻结 Summary / entry 修订身份，实际输入不同的工作不能仅按标题合并。

手动“安排重试”也遵循持久等待；已排队、等待或运行的重复请求不会清零 attempt 或缩短 nextAttempt。API 的重试响应表示 ACCEPTED，实际状态与下次时间从正常任务 / 入库详情读取，不再假报立刻 PENDING。

## 历史失败与恢复

原正常恢复扫描负责迁移历史 FAILED：保留历史行与累计次数，核对当前来源及原输入权限，按原失败时间计算等待；已经超出等待的旧任务在恢复后再错开8～10秒。已存在的 RETRY_WAIT 不重算或推迟 nextAttempt。扫描每轮至多100条并有游标，不能被前面的无效输入长期挡住。

过期的 RUNNING 尝试记录原失败、安排退避并释放其原知识库控制权；不重置所有运行任务、不凭进程重启或 Redis idle 偷走尚未过期的 owner。单次600秒及发布栅栏仍按数据库时钟执行。

无效原件、身份 / pipeline 无法确认、质量检查待人工处理、撤权 / 删除 / 被新版本替代、过期完整输入等，仍按原规则拒绝。Raw 在 claim 前再次核对当前资料与上传者，Generation 在原输入固定与发布栅栏核对全部来源。持续退避不赋予失效来源模型调用或发布权限，也不自动恢复取消的任务。原始内容 / 模型判定不合格不会用假数据改成成功。

## 历史产物不被覆盖

旧配额规则下曾产生的异常 Summary 已是 STALE / STAGED，其记录、事实、失败、模型收据、MinIO 字节与 Milvus 物理行继续保留。新规则不自动把它上架。

新生成 Summary 的不可变身份包含原 publication 任务 / epoch 命名空间。即使新尝试产生相同 Markdown，也不能覆盖或重新激活旧 STALE 行。已审计的 ACTIVE/PASS/PASS 检查点仍可按现有规则复用：下游入队故障后，正常同任务重试不会重复提取、审计、编码或上传。

## 真实组件回归与边界

```sh
python3 scripts/test-postgres-durable-retry.py \
  --jar-path java/ekb-bootstrap/target/evokb.jar \
  --output .tmp/acceptance-manual/durable-retry-new.json
python3 scripts/test-postgres-compilation-command.py \
  --jar-path java/ekb-bootstrap/target/evokb.jar \
  --output .tmp/acceptance-manual/command-new.json
python3 scripts/test-postgres-summary-handoff.py \
  --jar-path java/ekb-bootstrap/target/evokb.jar \
  --output .tmp/acceptance-manual/handoff-new.json
EKB_E2E_BROWSER_CHANNEL=chrome EKB_E2E_BASE_URL=http://127.0.0.1:18487 \
  npm --prefix web run e2e -- e2e/task-failure.spec.ts
```

PG 探针保留独立 schema 和初始化 SQL，显式使用合成失败及到期 / 崩溃故障：24并发普通 / repair / command 入队、18并发Raw claim、两类任务各超过五次、重启后持久时间、旧别名等待继承、撤权、原 owner失效与600秒不续约；没有真实模型或制造线上成功。半发布探针实际等待8～10秒退避，并证明同文重新生成不覆盖旧STALE。Chrome组件使用合成展示数据，覆盖第12次仍等待与普通用户不见管理员原始诊断。

这些组件通过不能替代真实后台恢复。最终运行 JAR、真实旧任务从第六次继续执行的结果、关联条目恢复或当前等待状态，必须在本批部署验收中另行记录；旧 PDF 失败、旧任务前五条失败和既有证据不能被改写。

## 条目描述与适用范围使用独立证明

V24 的 `kb_entry_source.scope_fact_ids` 专门保存已验证 `scopeAnchor.supportingFactIds`，`fact_ids` 仍只解释模型生成的条目描述。所有 anchor 都使用同一通用处理，不针对合同号/公司名值。Scope 工厂先核对原冻结 Summary 的 PASS 事实与逐字锚点；持久化/发布再核对同一 Summary、来源版本、当前 ACL、完整输入和七维审计。描述审计不会因范围证明字段而被放松。向量投影的证据使用两种用途的并集，页面分别展示，撤销仅范围证据也会禁止当前条目列表及详情。

V24 不回填历史事实：旧行默认 `[]`，保留原修订和来源权限可读性，页面明确提示“部分历史来源未保存专用范围依据”。这不算当前专用范围证明验收通过。原正常 Concept 整理/来源演化发布后，新增修订会保存实际验证的专用证明；现有管理员concept-projection持久命令也可在当前完整输入及权限下，对已存锚点用GraphScopeIdentity逐字重新验证，生成新修订及对应投影，不重新生成描述。旧修订的输入仍在build_input，旧来源（包括scopeFactIds=[]）保留在ENTRY_SCOPE_PROOF_REBUILT原生审计。缺可验证事实、原文或权限时明确拒绝，不猜测补证；保留来源的旧成员在各自正常重新整理前可能仍为 `[]`，不会伪造或无必要重复生成全部模型内容。过去完整输入/来源失效导致 STALE 的行为不变。
