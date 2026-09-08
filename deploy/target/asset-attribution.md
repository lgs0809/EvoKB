# 条目参与及下载归属（KB-03 子批次）

本批按项目全解第 6、7.3.2 节落地 PostgreSQL 来源目录和条目有效下载信号，不代表 KB-03 全部完成。主环境仍为 PostgreSQL + Milvus，地址 http://127.0.0.1:18481/enterprise/ask；模型配置保持 gpt-5.6-luna / gpt-5.6-terra、已有 Qwen3-VL embedding/reranker。

## 实际行为

V9 新增不可变资产修订、生成输入依赖、支持来源目录、实际展开记录和独立贡献五张表。`kb_asset_source` 是接口 `sourceRefs` 的权威记录，目录引用必须来自本次实际输入中的有效原文；全部生成输入另存依赖表，未被引用的输入撤权也会阻断旧投影。相同内容、来源与权限版本复用修订；变化生成新修订，旧目录不改写。

Concept 投影只包含名称、别名、类型和简介，原文证明按 PG 目录读取。生成服务使用真实摘要事实的证据；既有已核验 Concept 可通过管理员修复接口重建投影，无需重新调用生成模型。该修复入口仍检查当前输入权限，不改任务状态。

查询仅记录最终进入本轮证据组装的条目及其实际展开证据。普通 RAW 结果、未使用的召回条目、缺少可靠历史修订的旧投影都不补造参与。成功完整传输资料包后，在同一事务中保存回执、来源贡献和条目贡献。只有实际展开且支持来源进入该回答实际包清单的条目得到贡献，按用户＋回答＋稳定条目 ID 去重；不同片段、不同来源、重新打包、重复完成请求都不重复计数。没有给一份热门文档的所有条目广播热度。

## 本批历史版本与真实验证

本批执行时 JAR SHA-256：`a63bfb139e9b00d694b41020c8dc5497400b0f88db5c6c5c60baaefd4ea8f4ea`。完整 Java 验证 180 项：179 通过，1 个未启用的旧真实模型 smoke 跳过，0 失败/错误；跳过不计通过。另有独立真实模型和浏览器证据。

| 范围 | 本轮结果与边界 |
|---|---|
| 升级 | 24 项通过，原 20 张表行数及摘要保持，V9 未补造历史参与/贡献 |
| 真 PG＋真 Milvus 组件 | 27 项通过，另一个 JVM 重放 1 项通过；包括 32 并发、撤权、事务回滚、版本及目录校验。隔离夹具与固定向量明确标识，不作为模型闭环 |
| 来源下载回归 | 真 PG 25 项及独立 JVM 1 项通过 |
| 实际生成资产 | 9 项通过：真实 Summary、五个 Concept、六份 Qwen 1024 维非零投影、Wiki 20 条有证据结论及 MinIO 原始正文 Hash |
| 自然问答＋原文＋原生 ZIP | 12 项通过，查询 `41011b73-8c22-411c-9126-b2d78be0cb45` 在当前部署后创建，质保 24 个月、4 小时响应、48 小时方案及排除条件与原文一致；实际展开一个 Wiki，另五条目贡献为零 |
| 查询预算回归 | 当前实际回答的 13 项持久化、原文来源与回答核对通过 |
| 实际 HTTP 并发重放 | 11 项通过：正常 API 再打包，HEAD/Range 不记贡献，12 份并发完整 ZIP 字节一致，只增加一份新包回执，首次来源/条目贡献与原始回执归属全部保持 |

浏览器中的原合同资料实际完成 Summary、Concept、Wiki 任务；Wiki 第二次执行成功，保留实际重试记录。它们生成时使用前一部署 `5d279574…`，当前版本通过正常修复接口把五个已核验 Concept 编码、发布到 Milvus；当前问答真实调用模型，没有把旧回答回放算作新调用。

本批当时的 Graph 任务失败，原因为编译服务没有提供发布所需业务范围。后续独立批次已修复并通过真实浏览器重试成功，执行版本、拒绝记录和当前发布读回见 [业务范围与一致图谱发布](graph-publication.md)；此处原失败不改写成通过。

## 重跑和手动核查

每次输出使用新路径，不覆盖失败尝试。部署需完整 verify 和源码/产物绑定记录。账号在 Git 忽略的 `.tmp/target/permission-accounts.json`，不得把密码复制进报告。

```sh
mvn -f java/pom.xml spotless:apply verify > .tmp/target/entry-verify-manual-01.log 2>&1
python3 scripts/record-backend-verification.py --log .tmp/target/entry-verify-manual-01.log --output .tmp/target/entry-verify-manual-01.json
python3 scripts/test-postgres-asset-attribution.py --output .tmp/target/entry-pg-milvus-manual-01.json
python3 scripts/test-postgres-download-usage.py --output .tmp/target/entry-source-regression-manual-01.json
python3 scripts/deploy-target.py stop
python3 scripts/deploy-target.py start --verification .tmp/target/entry-verify-manual-01.json
docker exec -i evokb-target-postgres-1 psql -X -v ON_ERROR_STOP=1 -U evokb_target -d evokb_target < deploy/acceptance/asset-attribution-inspection.sql
```

登录管理员账号，在资料管理查看 `HT-MV-017采购合同.md`，知识生成入口及 Wiki 页可看本次结果。在问答中直接输入“请概括星桥 HT-MV-017 设备采购合同的质保条款：质保从何时起算，期限多长，停机故障的响应和恢复方案期限分别是多少，哪些情况不属于免费质保？请附原文引用。”，点击引用、查看原文件、下载参考资料。保留实际 query ID 和 ZIP 路径，再核对 PG；提问次数和下载次数会增加真实测试记录，重复同一回答的下载贡献保持去重。

已有本批原生 ZIP 可这样只读重验；并发脚本通过正常 API 重新打包同一回答，避免重放已经过期的十分钟资料包，不生成新的回答：

```sh
python3 scripts/test-target-asset-attribution.py --session 41011b73-8c22-411c-9126-b2d78be0cb45 --download-path "$LOCAL_DOWNLOAD_ZIP" --account admin --output .tmp/target/entry-browser-inspect-manual-01.json
python3 scripts/test-target-entry-replay.py --session 41011b73-8c22-411c-9126-b2d78be0cb45 --account admin --output .tmp/target/entry-http-replay-manual-01.json
```

`newBrowserQueryCreatedDuringCurrentDeployment` 是首次闭环的构建绑定检查；今后重新部署后检查旧查询时该项会失败，不能把旧查询误标为新部署的真实模型验收。新验收应在浏览器重新提问并传新 ID。

## 保留失败及尚未完成

初次组件 Java probe 的构造签名、测试依赖及 SQL 字段名、真实回答检查的 API 包装层假设等验收脚本错误均保留，并在修复后重跑。真实 Graph 失败独立保留。

稳定条目跨来源归并、Concept→Wiki 正常晋升时复用业务身份、活动发布指针及完整栅栏、夜间阈值和配额、Stream 恢复、tokenizer/MinerU/multipart 与正式评测仍待实施；资产修订表目前提供溯源，并不能冒称完整资产生命周期。大文件客户端中途断开尚未实际网络测试。

本批证据位于 Codex 工作目录 `work/evokb-implementation-20261002/entry-*`，包括源码基线、逐文件差异、构建日志、绑定清单、失败报告、只读 SQL、实际 ZIP 与脱敏页面截图。资料均为明确标记的虚构验收数据，未清库或覆盖已有业务记录。
