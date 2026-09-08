# 业务范围与一致图谱发布（KB-03 子批次）

本批修复真实关系生成失败，以及失败兼容数据被页面误读的问题，不代表 KB-03 或 EvoKB 整体完成。主环境保持 PostgreSQL + Milvus，浏览器地址为 http://127.0.0.1:18481/enterprise/graph 。

## 实际行为

关系生成采用框架原生结构转换和枚举 Schema。模型输出类型化业务锚点，服务逐一核对已通过 grounding 的摘要事实与原文证据。名称相同不能单独作为跨资料合并依据；身份由已核验业务范围、实体类型和名称确定，锚点顺序、证据编号和版本编号不改变同一范围的身份。合同和项目范围必须包含对象与相关具名参与方。普通提及没有可靠锚点时保留实际资料范围，不补全局默认范围；这不是法律主体消歧的完整实现。

只有实际文档身份能创建 DOCUMENT 节点。程序建立带原文证据的 DOCUMENTS 边，Concept/Wiki 不伪装成图谱节点。查询从当前已召回来源的文档身份开始扩展，固定两跳并保留方向、修订、截断信息和预算；问题文本不能冒充已召回种子。

V10 为 PostgreSQL 图表新增稳定对象与关系范围字段。GraphStore 在事务中发布节点和关系，并核对全部原文证据的当前来源权限。2026-10-03 补测发现发布图与兼容映射原本分属两个事务：第二次写入失败时，已发布图未回滚。修复使用既有 Spring TransactionTemplate，将两种投影纳入同一个短事务；模型调用在事务外完成。真实 PostgreSQL 故障注入先复现该缺陷，再验证所有投影一起回滚及正常重试恢复。旧失败产生的兼容实体和关系保留。图谱页面、资料加工计数、质量冲突和产物评测统一读取当前发布修订，先校验全部来源和 ACL，再计数和限制结果。证据详情接口补回实际原文件名，原文及原文件仍走正常权限检查。

## 实际验证和版本

验收合同为明确标记的虚构资料 `HT-MV-017采购合同.md`，Graph 任务为 `c3ddef3e-0a9f-4414-bbf7-235f41194db6`：

| 执行 | 实际结果 |
|---|---|
| 第 1 次 | 原实现缺少业务范围，发布失败 |
| 第 2 次 | `47849ffd…` 下模型范围未通过校验，coverage 失败，未发布；拒绝与失败记录保留 |
| 第 3 次 | `2a27cb92…` 下通过真实浏览器“重试此阶段”，Terra 生成成功；发布 4 个节点、2 条参与合同关系和 3 条 DOCUMENTS 边，全部关系有当前原文证据 |

`77f41f06…` 是首次包含原文件名及双投影事务修复的读回部署；后续部署、共同发布合同和实测见 [暂存发布记录](staged-publication.md)。第三次模型执行属于 `2a27cb92…`，不能说它在后续部署重新执行。检查脚本传原执行证明时，明确区分执行版本与读回版本。

完整 Java verify：188 项，187 通过，1 个未启用的旧真实模型 smoke 跳过，0 失败/错误；独立实际模型执行另列。真实 PostgreSQL 隔离 Schema 组件检查 34 项通过，覆盖双投影整体回滚与恢复、范围/端点不可重绑、重复修订、全部来源权限、撤权后各消费者一致阻断、固定两跳预算及实际文档种子。组件夹具不是人为制造业务任务成功。

V10 升级及后续部署使用只读快照比较原 67 张公开表既有列的数据摘要；Flyway 及新增列独立核查。实际模型任务、数据库/API/原文件检查、部署比较和浏览器证据保存于 `work/evokb-implementation-20261002/graph-*`。最新事务缺陷的失败/修复证明及新部署浏览器原文件截图保存于 `work/resume-20261003/graph-*`。验收脚本早期错误、模型失败和修正后结果分别保留。

## 重跑与手动使用

每次使用新输出路径，保留所有失败；不清库。账号在 Git 忽略的 `.tmp/target/permission-accounts.json`，不复制密码进报告。

```sh
mvn -f java/pom.xml -B spotless:apply verify > .tmp/target/graph-verify-manual-01.log 2>&1
python3 scripts/record-backend-verification.py --log .tmp/target/graph-verify-manual-01.log --output .tmp/target/graph-verify-manual-01.json
python3 scripts/test-postgres-graph.py --output .tmp/target/graph-component-manual-01.json
python3 scripts/snapshot-target-state.py --output .tmp/target/graph-before-manual-01.json
python3 scripts/deploy-target.py stop
python3 scripts/deploy-target.py start --verification .tmp/target/graph-verify-manual-01.json
python3 scripts/snapshot-target-state.py --baseline .tmp/target/graph-before-manual-01.json --output .tmp/target/graph-after-manual-01.json
docker exec -i evokb-target-postgres-1 psql -X -v ON_ERROR_STOP=1 -U evokb_target -d evokb_target < deploy/acceptance/graph-publication-inspection.sql
```

浏览器输入 `HT-MV-017`，点击查询、关系浏览，再打开合同双方关系的“查看原文”和“查看原文件”。知识生成页保留正常任务状态，资料详情关系计数应与 PG 发布事实一致。只有失败任务提供“重试此阶段”，不改库制造新成功。

## 仍未完成

稳定条目跨来源归并、正常 Concept→Wiki 晋升、夜间阈值与配额、600 秒完整发布栅栏、Stream/PEL 全故障恢复、真实 tokenizer/MinerU/multipart 和 240 题正式评测仍是独立待完成项，不能因本批通过而算完成。
