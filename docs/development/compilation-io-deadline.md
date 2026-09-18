# 编译阶段的远端调用与发布事务期限

2026-11-02：确认并修复 KB-03 中的远端调用和发布事务等待边界，沿用原数据库 600 秒 stage_deadline、owner/epoch 和最多五次业务尝试。没有续约、重置旧任务、增加第六次尝试或调整聊天模型。

## MinIO

固定 SDK 8.5.17 的默认连接/写入/读取期限各为五分钟，没有整个调用的 callTimeout。只取得响应头并不代表对象读完；分段响应、持续慢读或多次请求可能超出编译尝试的剩余预算。

编译作用域内每次请求先核对原输入权限，并重新计算剩余时间，再创建使用同一 OkHttp dispatcher/连接池的独立客户端配置，设置整次调用的 callTimeout。Stat 和 Get 分别重新计算，Get 的期限一直持续到响应体读完/关闭，不在响应头后解除。EOF 和异常使用捕获的原 authority 核对，拒绝迟到结果并保留阶段到期分类。没有作用域的正常上传/读取保留固定 SDK 的默认行为。

不能对共享 MinioClient 调用 setTimeout 来实现每个任务的期限，否则会让并发请求互相改变配置。本实现不更换桶、不改变对象身份、不把 MinIO 写入成功当作 PG 发布成功。超时的写入仍可能已产生隔离对象；只有原 PG 发布 fence 才能决定是否成为当前资产。

## Milvus

固定 SDK 2.6.22 的 withTimeout 原地修改 ConnectConfig，不能用于不同任务的共享客户端。SDK 使用的是 **io.milvus.shaded.io.grpc**，普通 io.grpc.Context 不会约束它。

`CompilationGrpcCall` 在发起 SDK 调用的线程附加该 shaded Context 的 deadline，取原 60 秒配置与当前编译剩余时间的较小值。SDK 返回或抛错后复核原 authority，finally 恢复原 Context 并取消定时器。定时器使用可删除取消任务的单个 daemon 调度器。共享客户端、通道和 RPC 配置不变，其他任务及普通查询不继承已过期的上下文。

目录初始化、描述、索引写入、查询、删除、搜索及迭代下一页通过同一入口。此批没有宣称迭代器 close 的释放 RPC 或 Raw 非编译作用域已获相同强制期限。

## JDBC 发布事务

捕获输入、检查原权限以及最终发布均通过原 Spring `TransactionTemplate` 执行。每次创建保留原传播方式的事务定义副本，并将原期限与当前编译剩余时间的较小值设置为 Spring 事务超时；不修改共享模板。Spring/JDBC 的秒级期限向上取整，不能把它称为毫秒级强制终止；数据库原有精确 stage_deadline、owner/epoch 及完整输入校验继续决定是否允许发布。

设置事务期限只读取当前作用域的剩余时间，不递归执行该事务自己的 JDBC authority 检查。普通远端调用仍先执行完整 authority 检查。没有编译作用域的调用保留原事务配置。

此处约束了新建事务内的 JDBC statement/锁等待；尚未证明连接池获取、网络分区、继承外层已启动事务时都可在相同期限内退出。事务回滚后不能把超时的任务视作成功，迟到外部产物仍不能绕过数据库发布 fence。KB-03 作为整体仍未关闭。

## 已实际通过的针对性回归

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) \
MAVEN_OPTS='-Xmx512m -XX:ActiveProcessorCount=2' \
mvn -f java/pom.xml -pl ekb-infrastructure -am test \
  -Dtest=CompilationGrpcCallTest,MinioCompilationDeadlineTest,CompilationExecutionBudgetTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dspotless.skip=true
```

7 项远端检查通过（4 HTTP、3 gRPC），另有 8 项编译预算检查通过（含事务期限设置不递归检查自身 authority）。HTTP 使用真实 loopback 服务和 MinIO SDK，覆盖已返回头但响应体挂起、过期/撤权前不发请求、正常读取及并发配置隔离。gRPC 使用真实 loopback 服务和实际 Milvus SDK，检查服务端收到取消、旧 Context 恢复、SDK 原 RPC 配置不变，以及过期 attempt 不进入 adapter 的外部调用。载荷是组件夹具，不替代真实 MinIO/Milvus 数据链路验收。

初轮 gRPC 夹具漏实现 SDK Connect 握手导致 UNIMPLEMENTED，修正夹具后通过，失败日志保留；未将夹具缺失误判为业务后端故障。完整构建和部署结果以本批验收记录为准。

真实 PostgreSQL 锁等待探针使用 `scripts/test-postgres-compilation-jdbc-deadline.py --jar-path <冻结JAR> --output <新报告>`。它保留独立 schema 和初始化 SQL，按原 API 正常 claim 一个 600 秒任务，并在嵌套 500ms 观察作用域里持有另一连接的来源行锁；验证 Spring 的一秒 statement 取消是否先于四秒服务端夹具保护，检查无半成品输入、无发布写入、原 attempt/deadline/epoch 不变。它不制造完成状态，也不替代真实模型生成。是否实际通过以对应验收报告为准。

`verify-backend.py --skip-format --output <新报告>` 可以在不改写工作区的前提下运行完整 clean verify、Spotless 检查、源码清单与 JAR 绑定。本批仅对实际涉及的 Java 文件执行 Spotless，而非全仓格式化。
