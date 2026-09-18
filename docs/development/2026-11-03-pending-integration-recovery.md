# 待关联来源整合、图谱恢复与筛选验收

对应KB-03、KB-05、KB-06。本批真实模型、正常worker、PG/Milvus/MinIO和浏览器证据分别保存；没有SQL直接写关联完成、图谱发布或成功状态。

## 问题与实现

- 新来源关联到已有条目时，原摘要文本没有变化，旧成功Concept任务被复用，新增关联停在pending。恢复器现在根据当前Summary、ACL和待关联上下文排持久任务；相同Summary已有在执行/退避中的任务时合并调度，不因上下文变化重复堆任务。分段游标每轮最多100项。
- 待整合条目在候选中明确标记并优先保留。模型结果必须实际纳入该来源；遗漏整合时不得将任务记成功，而是保存失败、按既有策略后台重试。没有通过硬编码合同或实体名称替代模型判断。
- 合同锚点引用了不含合同编号的事实时，校验继续拒绝；错误反馈额外列出当前请求内真正逐字包含该锚点的事实短键。下一轮模型自己纠正引用，程序不自动替换来源、不放宽身份校验。
- 原GRAPH/WIKI排队任务的条目修订已经过期时，调度器仅取消PENDING/RETRY_WAIT/FAILED旧任务，原因INPUT_SUPERSEDED并保留原错误。RUNNING和成功历史不改，现行修订仍按正常流程生成。
- 浏览器实际按关系类型筛选发现500：SQL条件和GROUP之间缺空白，命名参数误读为typeGROUP。统一在片段连接点加换行；真实PG回归覆盖一跳/两跳、类型/日期组合、未知类型、参数注入拒绝和时间左闭右开边界。

## 当前产物与测试

当前JAR `e4c46cf67ef7f687c6b93449174a04c5b528564fba19599a47e8dd48b5bc2a3a` 已按完整验证记录正常部署。完整403项＝402通过、1条件跳过（RealModelSmokeTest未启用），0失败/错误。真实PG图谱47项通过。上一包538daa00上的晚关联/调度恢复原生27项通过；新包与其619项编译资源逐字比较，只有JdbcPublishedGraphReader一个类变化，不能把旧模型执行改称新包执行。

原四条pending全部由真实后台整合并修复图谱。HT-ENTRY-104的Concept任务dad96235…在第10次处理完成；错误事实引用仍保留在原审计中。GRAPH c00b3458…经历SSL握手失败、正常指数退避，在第5次处理成功。旧修订GRAPH bf39c212…正常CANCELLED/INPUT_SUPERSEDED，未写成功。

真实输出核验：稳定条目12项通过；图谱9项通过，包括任务/权限/原文和摘要对象hash、完整输入、实际发布关系和普通用户API。普通用户与管理员在该公开案例均读到36条关系，浏览器可打开采购合同原文。新包额外18项实际API/只读SQL通过：一跳14条、两跳36条、供货1条、指定日期与类型组合1条、未知/注入类型为空、四条关联全部完成且旧任务正确取消。浏览器重复原失败步骤，日期2026-11-03＋向其供货正常显示1条关系。

一次图谱验证器错误地用累计Summary关系集合重算单次任务hash，导致失败。现在读取该次真实、未截断、hash完整的模型输出审计，用部署包编译器只读重放校验，禁止任何模型新请求和发布回调，再核对实际PG当前输出。真实重放9项通过，回执损坏/截断/错误模型或类型等16项原生检查与25项纯读取身份测试分别通过。原失败文件保留；累计关系不等于单次输出，没有为了通过修改任务hash。

同批早些时候还执行了39项权限撤回与5项撤权后真实问答检查，属于当时2c23dbc1包；新包没有把这39项冒记为重新执行。当前图谱普通用户/匿名边界由上述9项及新包API另外核验。

## 重跑与证据

```sh
python3 scripts/verify-backend.py --output "$OUT/evo-full"
python3 scripts/test-postgres-graph.py --output "$OUT/graph-native.json"
python3 scripts/test-postgres-late-source-association.py --output "$OUT/late-source.json"
python3 scripts/deploy-target.py check --verification "$OUT/evo-full.json"
python3 scripts/deploy-target.py stop
python3 scripts/deploy-target.py start --verification "$OUT/evo-full.json"
python3 scripts/observe-target-stable-entry.py --version "$ACTUAL_VERSION" --output "$OUT/observed.json"
```

OUT使用全新绝对路径。原生组件在独立保留schema运行，seed.sql是技术夹具，不能当真实模型成功。实际生成读回必须选择当前进程开始后真正执行的任务；历史任务不能套当前进程身份。部署会短暂停止本机服务，但不删除容器、卷、数据或重置任务。人工可在 `http://127.0.0.1:18481/enterprise/graph` 查询“恒岳材料有限公司”，选择关系类型与日期，再打开原文。

本地完整记录在工作区 `work/chains-20261103-1858/`：evo-integration-recovery-full-1/native-1、evo-final-stable-concept-proof-1、evo-final-stable-graph-proof-1/2/3、evo-graph-filter-native-red-1/green-1、evo-graph-filter-full-1、evo-current-filter-and-recovery-proof-1及同名SQL/日志、浏览器失败与最终截图。最终包只读筛选通过不等于全部正式图谱质量评测通过。

热度自动晋级、午夜运行、完整权限/图谱两跳矩阵、旧资产清理和正式基准仍没有全部认证；本案例不代替这些范围。模型仍为gpt-5.6-luna/terra与原Qwen，Milvus保持，没有新建替代项目。
