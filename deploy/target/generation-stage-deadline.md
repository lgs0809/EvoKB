# 数据库阶段截止与执行身份（KB-02 子批次）

本批沿用项目全解中最新确认的规则：单阶段 600 秒，不续约；原清单中的 10 秒心跳、60 秒 lease 已废止。本页真实到期与并发结果属于 `84d703e2…`；后续部署与多来源暂存发布见 [暂存发布记录](staged-publication.md)。环境为 PostgreSQL + Milvus，访问 `http://127.0.0.1:18481/enterprise/graph`。

## 已实现

V11 为原任务账本添加 owner、epoch、stage deadline 和来源 ACL 快照，保留原任务、失败历史和所有业务数据。开始执行使用数据库时钟，只有合法进入 RUNNING 才增加 attempt/epoch。不同进程启动不能重置仍有效的任务。既有单线程编译 worker 每次只领取一个任务，不让排队任务提前消耗阶段期限。

过期恢复每 15 秒检查、每页最多 100 条，并推进任务游标；已过期任务进入已有的 10/30/120/300 秒退避流程，最多 5 次业务尝试。deadline 和到期资格均以数据库时间判定，传入未来的主机时间不能提前重试。恢复不增加 attempt，已记录的失败不会重复落账。明确失败仍可以立即持久化，不必等待阶段到期。

完成、失败和短发布事务校验当前任务、owner、epoch 与截止时间；发布和完成另外核对来源仍有效、ACL 未改变。调用方不能替换冻结的 ACL。短事务在回调前后检查截止时间，数据库锁不会延长阶段期限。Graph worker 的已发布图与兼容映射已接入这道发布保护；模型调用不在事务内。

## 实际验证

- 完整 Java verify：188 项，187 通过，1 项真实模型 smoke 因未启用而跳过，0 失败/错误。
- 实际 PostgreSQL 阶段检查：20 项通过。真实等待原配置的 600 秒到期，再等待正常 10 秒退避，验证旧执行者不能提交、不能补写失败、恢复仅记录一次失败、新 owner/epoch 可以正常发布和完成。未通过 SQL 缩短 deadline 或制造成功。
- 实际 PostgreSQL 并发检查：8 个进程领取 8 个不同任务；8 个进程争抢同一任务只启动一次，共 6 项通过。组件夹具使用正常 queue/claim/publication/complete 方法，不冒充模型或业务流程成功。
- 实际图谱组件检查：34 项通过。V11 部署前后原 67 张公开业务表既有列的数据摘要不变。当前图谱数据库/API/原文件读回 15 项通过；历史真实模型执行仍归属原 `2a27cb92…`，不改绑到本版本。

证据位于当前 Codex 工作目录的 `work/resume-20261003/generation-*`；SQL 种子、运行日志、独立 schema、实际执行版本和源码 hash 都保留。早先未执行超期等待的检查明确写 `EXPIRY_NOT_RUN`，不能代替后续的真实等待结果。

## 重跑

每次使用新的输出路径，测试仅创建保留的隔离 schema，不清理业务数据：

```sh
python3 scripts/test-postgres-generation-stage.py --wait-deadline --output .tmp/target/stage-deadline-manual-01.json
python3 scripts/test-postgres-generation-concurrency.py --output .tmp/target/stage-concurrency-manual-01.json
python3 scripts/test-postgres-graph.py --output .tmp/target/graph-stage-manual-01.json
```

共享探针执行器冻结实际编译的 JAR 和探针源码字节，避免等待期间文件变化后把结果错误绑定到新文件。

## 未完成边界

这不是 KB-02 整体完成。后续 V12 已将 Summary/Concept/Wiki 的全部输入快照、短发布事务、按 task/epoch 对象暂存和 Milvus 当前指针纳入统一合同，实际结果见上述记录。原件解析/raw embedding、同知识库串行编译、资源取消、Stream/PEL/Redisson 与全部故障窗口仍未完整验收；ADMIN 同步加工接口也需纳入一致任务执行合同。
