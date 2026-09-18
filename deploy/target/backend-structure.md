# 后端职责与结构整理

现有Maven五模块边界保留。`ArchitectureTest`对domain不依赖外层/Spring、application不依赖适配器与Web/JDBC、infrastructure不依赖interfaces、interfaces不依赖infrastructure已有四条约束；本次不另造框架或重新切层。

| 模块 | 职责 |
| --- | --- |
| `ekb-domain` | 身份/来源/证据/任务等数据与业务值，避免反向依赖框架 |
| `ekb-application` | 用例、端口、Raw结构/编码策略、查询和编译决策，表达授权与执行合同 |
| `ekb-infrastructure` | PostgreSQL事务/owner期限、原生RedisStreams、Milvus投影、MinIO、MinerU与模型HTTP适配 |
| `ekb-interfaces` | HTTP协议、验证和视图映射，不直接绕过用例读写适配器 |
| `ekb-bootstrap` | SpringBean接线、配置、Flyway与生命周期启动 |

本次入库职责整理新增 `ClaimedRawIngestionExecutor`。同步兼容上传与持久异步worker都把“已有claim → 原metadata/分类 → 原parser任务回调 → 同一RawExecution → rawComplete”交给这个application用例。它不领取任务，不开新attempt，不修改期限，不处理RedisACK，也不把commit后关联或临时文件清理塞进执行事务。两个入口原有错误记录/中断、关联和清理行为分别保留。worker方法名从旧Summary名称准确改为Association，实际仍只调用现有选择性关联触发器。

已有PG epoch/固定600/当前source-ACL短发表事务仍是执行权依据；`JdbcRawPublicationWriter`继续供Raw与rebuild共用。Redis使用原生Streams/PEL，短Redisson lease仅减少扫描争用；`SmartLifecycle`负责持久worker启动停止；模型共享HTTP使用已有SpringRetry总预算。此处没有为了“重构”替换这些框架机制，也没有把调度锁改为执行权。

仓库脚本原本平铺182个入口，有多处重复JAR解包、个人JDK路径、日志和资源参数。新增只读 `native_probe_runtime.py`集中不可变工件解包/SHA与有限资源编译执行，先迁移两个离线Raw脚本；另将20份旧探针的固定安装路径改为统一Java17发现，保持旧CLI、资源边界和report字段。fixture、overlay、种库和通过判断仍由各脚本显式负责。新增脚本导航按职责分组，不移动历史入口；已冻结Hash计划先保存原脚本和SHA，不用当前脚本重新认证旧实验。

查询HTTP已从资产控制器提取到`EnterpriseQueryController`，请求/响应合同由`QueryHttpContracts`承载；`EnterpriseEvidenceViewAssembler`共用授权后的证据投影。现有16条查询路由保持。集中契约`Ports`与管理适配器仍较长，本页不将它们描述为已经全部拆分；入库提取不更改公开接口。

结构回归和行为验收分开：共享native工具以实际JDK/不可变JAR及明确ZIP安全fixture验证，0模型/0业务写；新的claimed executor以限定unit验证参数、同claim、回调与失败传播，不能替代历史真实600/发布测试。该批源码的后端完整验证18已完成288项：287通过，1个真实模型smoke显式跳过；不可变JAR `3cc086d0bc6747dcca4bc80568d83785004d4c763d64f257f6d6a4c41bfa477e`已正常部署。随后JDK脚本与Java注释的整理不是新的业务验收，也不把未执行的模型评估算通过。
