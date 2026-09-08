# 查询证据、预算与依赖故障（KB-04）

`QueryEvidenceContext` 负责一次选择完整原文、按问题相关性排序、去重、预算、渲染和连续来源编号。覆盖判断与回答使用同一实现、同一字符上限；只能引用实际发送的来源。原文过大或剩余预算不足时整段跳过，不截断原文、金额、条件、表头或否定句。

最多 8 段；6000 token 上限按已有冻结 Qwen 本地 tokenizer 对每段完整编码输入计数，包含其模板开销，因而比纯正文计数保守。这是可重现的证据容量约束，**不声称是 Luna/Terra 服务商的实际 prompt token 用量**。生产从 `FrozenRawEncodingConfiguration.tokens()` 注入，核验已有资产，不下载/替换模型。旧独立构造器使用明确命名的 UTF-8 字节上界，不冒称真实 tokenizer。

检索或回答的真实 `TransientDependencyFailure` 最终保存为 `FAILED`，显示“查询未完成”，保留已消费的唯一检索额度和现有原文，不退款、不把中断解释为知识不存在。覆盖判断两次结构尝试中如有真实依赖错误且没有后续有效结果，同样保留 FAILED；普通不合法结构回复继续有界降级，不能仅因检索到资料就伪造完整答案。依赖恢复后点击“重新查询”开始新查询，旧失败记录保留。

## 实际回归与部署

```sh
mvn -f java/pom.xml -pl ekb-application -am test -Dtest=ApplicationParityTest,KnowledgeQueryOrchestratorTest -Dsurefire.failIfNoSpecifiedTests=false '-DargLine=-XX:ActiveProcessorCount=2 -Xmx512m'
python3 scripts/verify-backend.py --output .tmp/target/verify-context-manual-01.json
python3 scripts/deploy-target.py check --verification .tmp/target/verify-context-manual-01.json
python3 scripts/deploy-target.py start --verification .tmp/target/verify-context-manual-01.json
```

环境需 JDK17；已有受管进程需先在无活动任务时正常 stop。输出路径每次换新，不能覆盖以前的失败。

修复前两个红色用例真实失败：未转交的第二段可被引用、第九段可被引用。修复后这两个与中文全段/6000边界、覆盖与回答上下文相同、一次依赖故障恢复/两次最终失败等回归全部通过：27 项、0 跳过/失败/错误。完整 verify 第10轮实际243通过+1未启用真实模型测试跳过；部署产物 `71e2b25c…`。这些是自动回归，不能作为真实回答质量通过。

上一产物 `3e981429…` 的实际 CUA 503 故障查询 `9014913c-ce5e-4f88-a33e-2dea42f426a3` 保存 FAILED。正常恢复先遇到另一次依赖中断 `d2a0f84c…`，其失败未删除；随后正常查询 `d488de50…` 真正 ANSWERED，数据库预算13项、当前来源8项、实际ZIP完整回执与原件16项通过。代码和产物边界保存在报告，不把后续读取说成重新推理。

历史部署 `71e2b25c…` 的首次真实自然语言查询 `4a6fe2d8…` 遇到真实依赖中断，页面显示“查询未完成”，截图 `entry-query-context-cua-interrupted-1.png` 保存。修复共享模型加载与驻留管理后，同页面点击“重新查询”，新会话 `3c112102-f93e-4bf1-b2de-111c3b0045ec` 于 08:29:05 UTC 真正 ANSWERED，回答保留 30→15 日、72 小时、书面验收与合法发票、900 万和 24 个月。预算 13 项、当前引用及编号 8 项、实际 CUA 下载 ZIP/原件与数据库回执/独立贡献 16 项均通过。截图 `entry-query-context-cua-recovered-1.png`、只读 SQL 和原件包保留在同一工作目录。这是新版本上的一次实际恢复闭环，不代表全部正式题目或 5 秒性能通过；后台原生重排超出 HTTP 期限后仍占用许可，不能提前释放再启动第二个 kernel。

2026-10-03 后续 CUA 查询 `f78c262a-9760-4c82-b181-736f7aa9d2ea` 在共享编码依赖繁忙时实际 FAILED，8 项持久失败状态校验通过，0 下载贡献。该页面输入时追加到了旧问题末尾，保留真实提交文本，不冒称独立新问题或回答质量通过。当前部署 `3854403e…` 上使用替换输入重新完成两轮不同自然语言查询：质保起算与是否修改 `048b0d36-5a3e-47ec-b482-cc40b08e1a31`、单方邮件与签署协议生效 `bd8f8aed-ff50-488a-9754-22efddc318b9`，每轮分别 13+8+16 项通过；实际浏览器下载为 `knowledge-sources (11).zip` 和 `(12).zip`，原件字节、数据库回执与独立来源贡献一致。报告 stem 为 `entry-warranty-cua-*`、`entry-amendment-cua-*`，不计正式冻结问答集。

同一当前部署上的第三轮独立问题 `069d7f04-eb92-47c8-ab79-524a385f79a0` 实际回答：供应方不能要求采购方在未取得合法有效发票时按 15 日付款，期限仍顺延。真实 CUA 下载 `knowledge-sources (13).zip`，预算 13、引用 8、原件包及回执 16 项分别通过；报告 stem 为 `entry-invoice-condition-cua-*`。三轮实际下载后，事件条目自己的有效贡献为 6，原合同条目自己为 3；未直接修改热度，未把一次来源下载广播给未参与条目。回答本轮正确不意味着既有摘要的主体颠倒已被修复。

事实核验另外暴露真实质量失败：当前摘要把“不能要求采购方无发票付款”改成“采购方不能要求无发票付款”。生产核验提示已增加主体、客体、否定与条件一致性约束；实际 Luna 两候选诊断中，原文候选通过、错误主体候选仍被误判支持，1 通过/1 失败（`entry-actor-grounding-real-model-1.json`）。这个问题仍未解决；不能把自动核验字段或正常引用访问当作改写含义正确的保证。

## 正式评测与手动核验

正式清单位于 `deploy/acceptance/formal-evaluation/manifest.json`，原文、业务关联、权限和 Gold 在模型执行前冻结。初始化逐份用 staged API 保存权限后 start；同 Hash 可复用，冲突或失败任务拒绝覆盖。可用重复的 `--filename` 精确选择只读审计确认的未启动资料；`--stop-after-current-source-file <标记路径>` 只在当前来源正常终态后停止下一份启动，退出码 2 表示安全边界停止，不算通过或任务失败。重新执行使用新报告目录并保留原 file/task 身份，禁止重置失败次数。正式问答走自然语言 API，保持原件、模型角色、tokenizer、预算、JAR 和来源发布状态；失败/超时/澄清在分母内，未启动单列。不会下载预热留出题，也不把开发 smoke 计正式集。

```sh
python3 scripts/seed-formal-evaluation.py --manifest deploy/acceptance/formal-evaluation/manifest.json --output-dir .tmp/target/formal-import-manual-01
python3 scripts/run-formal-query-evaluation.py --manifest deploy/acceptance/formal-evaluation/manifest.json --condition baseline --output-dir .tmp/target/formal-baseline-manual-01
python3 scripts/test-target-query-budget.py --session <真实查询ID> --account confidential --expect ANSWERED --output .tmp/target/query-budget-manual-01.json
python3 scripts/test-target-query-citations.py --session <真实查询ID> --account confidential --output .tmp/target/query-citations-manual-01.json
python3 scripts/test-target-query-failure-state.py --session <真实失败查询ID> --account confidential --expected-reservations <实际已保留轮数> --output .tmp/target/query-failure-manual-01.json
```

`run-formal-query-evaluation.py` 的评分是保守的文字事实与原文引用核验，不能冒充通用语义裁判、Ragas 或历史5.1→1.8收益。已有开发单题真实通过，仅说明该独立问题可用；140问答 / 240主集仍待正式完成。原始观察脚本和只读 SQL 保留，不直接改查询、审批、生成或下载状态。
