# 开发与验收脚本

旧CLI入口保留在本目录，历史报告中的命令仍可找到。不要通过移动文件破坏冻结campaign或资料里的复跑路径；公共运行工具与具体验收职责分开维护。

| 范围 | 主要入口 | 行为/边界 |
| --- | --- | --- |
| 构建/本机受管部署 | `verify-backend.py`、`deploy-target.py`、`web_release.py` | 验证源码稳定与不可变产物；部署须确认活动任务终态，具体条件见target文档 |
| 默认部署配置 | `check-default-deployment.py`、`make compose-config` | 只解析默认PG/Milvus/Redis/MinIO与app合同，不启动/build容器，不代表新的实际部署验收 |
| 关联业务初始化 | `seed-target-business-data.py`、`seed-acceptance-accounts.py`、`seed-formal-evaluation.py` | 正常API/隔离种子，正式资料不得用SQL写成功状态；凭据不输出 |
| 通用原生运行 | `native_probe_runtime.py` | JAR读一次、类/依赖SHA、Java17定位、有限资源compile/run及失败日志；不加载模型、不种库、不判定验收通过 |
| 隔离PG组件 | `postgres_component_probe.py`及`test-postgres-*.py` | 冻结JAR迁移；source overlay/源迁移必须显式声明，fixture不冒充真实业务流程 |
| Raw/Stream恢复 | `test-raw-task-ownership.py`、`test-raw-stream-matrix.py`、`test-stream-30-native.py` | 原owner/epoch/DB600与PEL、故障恢复，fixture模型范围另列 |
| 分片/分类/原件 | `test-target-multipart-api.py`、`test-target-initial-classification.py`、`test-native-upload-classification.py` | 正常身份与完整存储证据；UI混合API预置需明确披露 |
| 解析/tokenizer | `start-mineru-service.py`、`test-mineru-*.py`、`test-existing-tokenizer.py` | 实际固定模型与完整原件；失败/超时/资源身份保留，不把ready200算解析成功 |
| 切块/缓存机制 | `test-chunk-40-native.py`、`test-hash-40-native.py`、`test-hash-inputs-native.py` | 离线token/结构或fixture向量证明，不代表Qwen收益或问答质量 |
| 实际Hash编码对照 | `test-hash-efficiency-native.py`、`test-hash100-resumable-native.py`、`run-hash100-batches.py` | `--real-embedding`必须协调真实窗口；fixture/真实不可混合，正式百份当前未执行 |
| 模型合同/HTTP回归 | `test-raw-atomic-junit.py`、`test-raw-http-body-deadline.py`、`test-hash-atomic-identity.py` | 模型响应字节重放与loopback fixture不算新推理；需要完整同次证书 |
| 资产/查询/权限读回 | `test-target-*.py`、`observe-target-stable-entry.py` | 正常API/只读PG-MinIO-Milvus，按实际artifact绑定，不混算组件与全链 |
| 正式评估冻结 | `prepare-*-30.py`、`prepare-formal-*.py`、`run-formal-*.py` | 开发/留出分开，source/case/断言数分开；不事后改Gold填通过 |

`*.java`是有明确作用域的原生探针，不是额外产品服务。每次结果使用新stem；源码/工件/原件/模型身份变化须新实验或明确拒绝续算。数据库schema、对象、失败记录和原任务谱系不能为方便测试被清空。

共享运行工具的轻量回归：

```sh
python3 scripts/test-native-probe-runtime.py \
  --jar /absolute/path/to/verified/evokb.jar \
  --output /absolute/path/to/fresh-runtime-result.json
```

Java17定位顺序为`EKB_PROBE_JAVA_HOME`、`JAVA_HOME`、系统发现；所有路径都校验实际`javac`版本，显式配置错误时拒绝执行。20份旧探针的固定JDK常量也使用这条规则，不依赖某位开发者的安装路径。公共工具编译128MiB/2CPU，执行默认256MiB/2CPU；旧探针各自的原资源边界保持。命令超时保留日志，调用者继续拥有测试条件、Gold、预期终态以及是否触发真实模型。

不可变JAR解包/编译执行工具先接入`test-hash-inputs-native.py`与`test-chunk-40-native.py`；其余20份探针本批只集中JDK发现，保留原CLI、取证和执行逻辑，不描述为全部运行器重构。已冻结实验先归档原脚本；源码身份变化不覆盖旧计划或失败。参见[后端职责](../deploy/target/backend-structure.md)与[Raw验收状态](../deploy/target/raw-work-status.md)。
