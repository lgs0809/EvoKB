# 知识演化机制的复跑与检查

2026-10-03 的 `evolution-thirty-full-1` 实际完成 30 个独立案例：19 开发、11 留出，67 项断言全部通过。冻结题单位于 `deploy/acceptance/formal-evolution/evolution30.json`，SHA-256 为 `025625143fa5572dc52256bf8474ea6c6f1708780e8eff09eae9f2fff392506b`。实际读取产物为 `3854403e8a31e1862e184c11c8574fadc26ce5ac09de9a8b9a588ee93c393f19`，不是之后待部署的源码。

覆盖来源三次下载门槛、条目自身五次使用门槛、重复/不完整传输与事务回滚、来源与条目热度隔离、权限/版本/删除失效、已有 Wiki、修复优先级，以及四个并发调度器共享每日 Summary/Wiki/Graph 的 20/10/10 配额。重新实例化调度器后同日没有重复消费配额。

执行的是生产 PostgreSQL repository、完整传输回调和 scheduler，但每例使用一个独立保留的 schema。查询、摘要、概念、传输字节均为合成组件夹具；相对年龄与失效状态只写入该独立 schema。没有调用模型或修改 public 的业务热度与任务终态。结果不能替代真实午夜、等满 30 天、模型内容质量、浏览器下载或 Milvus 发布验收。

## 复跑

从仓库根目录运行；每次使用新的报告目录，保留失败记录。脚本读取 `.tmp/target/process.json` 对应的不可变 JAR 及其迁移文件，完整保存实际编译的 helper Hash、种子 SQL、编译和运行日志。

```sh
python3 scripts/prepare-evolution-30.py \
  --output deploy/acceptance/formal-evolution/evolution30.json
python3 scripts/test-evolution-30-native.py \
  --manifest deploy/acceptance/formal-evolution/evolution30.json \
  --output-dir .tmp/target/evolution-thirty-manual-01
```

第一条只核对已冻结题单，不导入业务数据；原题单不一致时拒绝覆盖。第二条逐例创建新 schema 并保留数据。单例排查可以增加 `--case EVOLUTION-30`，但单例结果不能报成完整 30 例通过。

## 手动只读查看

每例 JSON 的 `postgresSchema` 给出实际数据库名称。以下命令已针对完整并发案例执行成功；只读事务和 schema 名称检查会阻止该入口落到 public。

```sh
docker exec -i evokb-target-postgres-1 psql -X \
  -v ON_ERROR_STOP=1 \
  -v component_schema=evokb_evolution30_e52332f8d035 \
  -U evokb_target -d evokb_target -f /dev/stdin \
  < deploy/acceptance/evolution-runtime-inspection.sql
```

输出包括来源独立贡献、条目自身贡献、候选、实际业务日期的配额选择和任务 owner/epoch 相关状态字段。若复跑了其它案例，替换 schema 参数即可。不要修改这些保留数据去制造通过状态。

本轮逐例报告及 SQL 位于 Codex 任务目录 `work/resume-20261003/evolution-thirty-full-1/`，手动读取日志为 `manual-readonly-case30-1.log`。真实业务环境另用 [运行检查 SQL](../acceptance/target-runtime-inspection.sql) 查看；两类结果应分别记录。
