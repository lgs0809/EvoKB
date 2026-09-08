# 检索轮次预算与真实验收

本批继续实施 KB-04 中的检索预算，使用当前 PostgreSQL + Milvus 环境。没有把 KB-04 整体标记为完成。模型仍为 `gpt-5.6-luna` / `gpt-5.6-terra`，Embedding、Reranker、原始数据和前端部署保持已有配置。

## 实际改动

- 新增领域预算策略 `RetrievalBudget`，以持久化占用记录计算轮次、子查询额度和重复问题。默认及硬上限均为八轮；已有每次查询最多八个子查询的保护仍保留，每轮最多三个子查询，没有强制每题跑八轮。
- Flyway V6 新建 `query_retrieval_reservation`，回填已有完成轮次的原 ID、输入和时间。原 `retrieval_round` 记录不改写，升级保留之前已用额度；有旧轮次间隙时按最大已用编号继续。
- JDBC 在短事务中锁定当前用户的 RUNNING 查询，计算额度、插入占用记录，提交后才调检索。使用 Spring `REQUIRES_NEW`，外层事务回滚不会退还已派发额度。失败及中断留下的占用始终计入。
- 完成记录必须匹配占用的 ID、轮次、查询输入和开始时间。相同完成请求幂等；不同结果不能覆盖原轮次，也不能凭空写成功记录。
- 没有新线索时保持 `INSUFFICIENT`，保留模型指出的缺口和现有原文片段及引用；片段明确标注为尚不足以得出完整结论，不把存在若干证据自动当成完整回答。达到预算时同样保留缺口。

本地 Embedding/Reranker HTTP 429/502/503/504/524 与连接异常统一有限重试（最多三次）。四条一批的 pairwise 重排保留全体候选及原顺序，不更换模型、减少候选或伪造分数。依赖仍不可用时交付已有原文、先前缺口和技术中断提示；模型覆盖判断失败也不会把任意证据自动当成足够。

这是预算持久化和有限恢复读取，不是完整查询恢复调度：服务启动后自动接管 RUNNING 查询、执行者身份栅栏，以及入库/编译的 Stream 协议仍待实施。组件强制退出证明额度不会重置，不证明真实模型查询会自动续跑。

## 本批验证与实际命令

从仓库根目录执行，输出路径每次换新，保留失败尝试。

```sh
mvn -f java/pom.xml verify > .tmp/target/budget-verify-manual-01.log 2>&1
python3 scripts/record-backend-verification.py --log .tmp/target/budget-verify-manual-01.log --output .tmp/target/budget-verify-manual-01.json
python3 scripts/test-postgres-retrieval-budget.py --output .tmp/target/budget-pg-manual-01.json
python3 scripts/deploy-target.py stop
python3 scripts/deploy-target.py start --verification .tmp/target/budget-verify-manual-01.json
docker exec -i evokb-target-postgres-1 psql -X -v ON_ERROR_STOP=1 -U evokb_target -d evokb_target < deploy/acceptance/retrieval-budget-inspection.sql
```

实际 Java 验证为 172 项：171 通过、1 项因没有启用 `EKB_REAL_MODEL_SMOKE` 跳过，0 失败/错误。跳过不计通过；真实浏览器模型调用另行验证。

真实 PostgreSQL 组件 18 项通过，另有强制退出前的提交检查通过。组件使用独立保留 schema `evokb_budget_probe_ee41270f91ad`，种子 SQL 与运行日志均保留。32 个并发请求恰好分配八个名额；组件 JVM 占用第一轮后 `halt(23)`，第二个进程读取到该记录并分配第二轮。还验证了旧版第七轮回填后只剩第八轮、子查询上限、原子去重、越权拒绝、外层回滚不退款、完成幂等及冲突拒绝。

升级部署前后，查询、对话、完成轮次、证据引用、ALL 输入依赖、文档、版本、元数据、原文块、Evidence 共十张表的逐行摘要与行数相同。V6 正常应用，五条旧轮次全部回填；共 12 项升级检查通过。

本批终态验收 JAR：`5badf898c24ce766ffa3a53173c0bd4680ceec35a16d4e6537a4dba0ae3f40bd`，地址为 http://127.0.0.1:18481/enterprise/ask。旧完整问答与实际 ZIP 回归 25 项通过，未把旧问题回放算成新模型执行。后续当前部署版本见主 README，保留本批原始构建绑定。

## 浏览器手动测试

使用已有私密测试账号文件 `.tmp/target/permission-accounts.json`，通过页面正常登录。不要把密码复制进交付记录。保密测试用户当前 clearance 为 10；他可以查看公开采购合同，旧保密回答和下载仍处于不可访问状态。

正常问题：

> 星桥 HT-MV-017 设备采购合同结合补充协议，验收后现在几天支付尾款？取得发票是否是前提？请同时说明合同总价和质保期，并引用原文。

在本批第一版 JAR `0acafdbaa15ceb622e4e69bd224be191cade2d73617ce95442242d95bb732482` 上通过 CUA 真实提交的新查询 `573e6934-ae50-485a-be97-01fe48fc4712`，一轮后回答 15 日、合法发票前提、900 万元及 24 个月，五条引用来自真实入库的两份合同。浏览器点击引用和“查看原文件”后能看到完整补充协议；“已解决”反馈已通过正常 API 保存。

```sh
python3 scripts/test-target-query-budget.py --session 573e6934-ae50-485a-be97-01fe48fc4712 --expect ANSWERED --expect-fact 15 --expect-fact 发票 --expect-fact 900 --expect-fact 24 --output .tmp/target/budget-query-manual-01.json
```

该真实新查询的 API、持久化答案、预算占用、完成轮次、引用及 ALL 模型输入依赖交叉核对 13 项通过。不宣称该正常问题执行了八次真实模型检索。

资料缺失问题也已通过 CUA 自然语言提交。初次查询 `60023187-ed56-4a14-afe8-581fcdc43080` 第二轮遇到本地 Embedding HTTP 503，旧代码中止为 FAILED；原失败截图、查询及占用记录全部保留。修复后的查询 `7561990e-6964-48aa-98ee-14160ffce936`（JAR `1c6d8bad8b1e6748760dc299faa669b8d5b3d206583a5a8b6e7f24aaaa8e6d51`）实际重试三次后明确技术中断，并保留八条原文和违约金条款缺口，13 项当前 API/数据库检查通过。这是故障保护通过，不算“依赖正常时因证据缺失而停止”的验收。

最后一版小批重排部署后，真实新查询 `c8561809-b99e-45d9-bfa0-044e1a10d4af` 从 2026-10-02 10:27:54 UTC 执行到 10:38:09 UTC，四轮共八个子查询全部完成。最终以 `INSUFFICIENT` 停止，保留每日违约金比例、累计上限与补充协议修改范围的明确缺口及八条原文；没有技术中断提示或虚构违约金值。13 项 API/数据库核对通过，报告标记为当前 JAR 创建的新查询，浏览器终态截图已保留。这项验证了依赖正常时补查并在预算内停止；不宣称真实执行了八轮。

```sh
python3 scripts/test-target-query-budget.py --session c8561809-b99e-45d9-bfa0-044e1a10d4af --expect INSUFFICIENT --expect-fact 违约金 --expect-fact 检索预算 --output .tmp/target/budget-missing-manual-01.json
```

## 失败、未测与剩余

- 失败后修复：真实第二轮 Embedding HTTP 503 未重试且丢失已有证据；修复后真实页面与持久化记录保留原文、缺口及技术中断，原失败不抹去。
- 失败后修复：只读核验脚本还遇到 Python 3.9 不接受 PG 五位小数时间戳，统一补齐精度后检查通过。
- 失败后修复：初次只读检查 SQL 误用了不存在的 `required_clearance` 字段，真实字段为 `security_level`。两次失败的 SQL/日志保留；修正后查询核对 13 项、完整只读检查均通过，没有写业务状态。
- 未测：真实模型连续八轮场景、多个 HTTP 请求同时继续同一个澄清会话，以及后端异常退出后的自动查询接管。不把组件并发分配测试混称为这些验收。
- 未完成：原生 multipart、真实 tokenizer 的 600/900/80 分块及 6000-token 证据预算、MinerU 常驻解析、240 条固定评测与 12 上传/12 Graph 验收。
- 未完成：KB-01/02 的完整 Stream/deadline/owner/epoch 发布治理；KB-03 的成功下载贡献、PG 资产来源目录、选择性沉淀和新版 Graph 页面。

实际命令、构建绑定、失败记录、SQL、脱敏截图与每个改动文件的差异保存在 Codex 工作目录 `work/evokb-implementation-20261002` 中的 `budget-*` 文件。
