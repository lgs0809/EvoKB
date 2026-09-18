# 百份开发资料的可恢复对照

该实验和主Hash40/正式QA留出集分开。仅使用预先冻结的100份专用DEV资料及五档版本；600份原件SHA、实际解析得到的完整输入SHA/native token、不可变JAR和六资产tokenizer必须相符。每组5份，共20组；每份baseline＋0/1/5/20/100五比例，两种方案合计1200来源case。预计输入数不是已发生模型调用数。

当前实验冻结的是历史`3854403e…3c393f19` JAR和原输入计划。生产strict atomic功能需要另做正常API验收；不能把历史实验向量补造认证，也不能拿新包运行旧输入计划后混算同一campaign。

本轮已暂停大规模验收。JDK脚本结构整理前，原prepared计划对应的四份入口/辅助源码按其原SHA保存在该计划目录的`frozen-scripts/manifest.json`中，原`plan.json`、0执行状态与失败报告未修改。当前脚本SHA已改变，旧计划的身份校验会明确拒绝混算；归档不代表实验完成，也不能跳过守卫继续旧campaign。恢复评估时需明确选择冻结旧入口或新建具有完整源码身份的新计划，并重新协调模型窗口。

先准备新目录，不请求模型：

```sh
python3 scripts/run-hash100-batches.py --prepare-only \
  --jar /absolute/path/3854403e…/evokb.jar \
  --fixtures /absolute/path/raw-hash-development-1 \
  --input-plan /absolute/path/raw-hash-100-input-native-2.json \
  --tokenizer-manifest .tmp/tokenizer/qwen3-vl-embedding-9f2f7e710d6d-1/manifest.json \
  --state-dir /absolute/path/new-hash100-campaign
```

协调共享模型空闲窗口后，按同一组冻结参数将`--prepare-only`改为`--real-embedding --batch-id HASH100-BATCH-01 --max-batches 1`。默认每个实验进程总编排上限7200秒；**生产阶段仍600秒、业务尝试仍5次**。继续其余组使用相应batch ID，重复同一命令会跳过已完成批，未完成批使用原checkpoint/schema/file接管。未测批不会自动算通过。

每组每次输出使用新stem，旧报告、checkpoint、HTTP启动/完成记录及错误保留。缺认证或profile/model/runtime变化拒绝续算；未知身份不替旧缓存补stamp。`latest.json`为当前位置，`attempts.jsonl`为追加账本，报告里的attemptRecords与case数分开。预检实际模型成本单列，并计入总成本；未接受的成功响应和发表前失败的编码也保留成本。

在state目录创建`stop`文件可让当前完整来源自然收束后退出，不取消模型kernel或改任务状态。恢复前移走该实验停止标记；不清业务数据或数据卷。进程异常退出后，RUNNING任务只有实际600秒到期才可被正常Repository恢复；耗尽5次的任务保留隔离，脚本不新建file绕过。

`--fixture-vectors`只验证编排/数据库/原件/索引机制，模型请求为零，不能与真实模式混入一个state目录，也不算效率收益。已完成的历史独立JVM退出实验按原file实际等满600秒，12/12来源case和12项谱系读回通过、13次执行；旧探针失败保留。全量与Hash两份真实小对照各452.605/451.768秒，未观察到显著提速。正式100份实际编码仍未执行。

完整原Gold、失败、版本与限定范围见[切块与Hash实验](chunk-and-hash-experiments.md)。
