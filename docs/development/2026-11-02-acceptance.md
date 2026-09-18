# 2026-11-02 第一批本地修复与真实验收

当前最终部署、协议修复、正常 Summary / Concept、自动 repair 越额与当前条目 STALE 的结果见 [最后发布记录](2026-11-02-closure.md)。以下按第一批结束时保留，不能当作最后状态。

本批沿用 PostgreSQL / Milvus / MinIO / Redis 和既定模型。保留原工作区改动、原数据卷、历史失败及五次隔离记录；没有 Git reset、推送、清库、续约、手动增加业务尝试次数或修改秋招资料。后续发现的自动 repair 越额缺陷另见最后发布记录。

## 实际部署

当前地址：`http://127.0.0.1:18481/enterprise/ask`。

| 产物 | 实际结果 |
|---|---|
| 最终后端 | JAR `bc8c9e49b03acd5ab20b9ecf75b5f1e0d06beda0ac28fd6f0d083c173100c34e`；369 项测试，368 通过、1 个未启用真实模型环境的 smoke 跳过、0 失败/错误。完整 `clean verify`、Spotless 检查及源码前后字节绑定通过。 |
| 正常发布 | PG 中查询、入库、编译在途均为 0 后，正常停止本环境旧进程，再由原部署脚本启动不可变 JAR；PID 58199，2026-11-02 20:23:11 +08:00。启动健康只计为部署检查。 |
| 第一批历史产物 | `cc192662bf3abde61af587e68f4399b0e1ea9517685a55d82f5a4589409854e3`；368 项中 367 通过、1 跳过。前两份新资料上传及真实问答属于这个产物，不挪到最终 JAR 通过数中。 |
| 前端 | 复用原正常发布的前端；本批没有改页面源码或冒称重新执行完整前端测试。真实 CUA 完成登录、上传、资料详情、自然语言查询和引用原文查看。 |
| 解析服务 | 原 MinerU 3.4.5，bundle `30033d113bf28c066a3fa1eba8c2da664e70f019a0b9de7b7c886cd821fbcb1f`，原 manifest、MPS、4 CPU 线程及 600 秒上限不变。 |
| 检索模型 | 原 `Qwen/Qwen3-VL-Embedding-2B`，revision `9f2f7e710d6d81056aa5c0a4f04764fec6bb7bda`，1024 维；原 Qwen3-VL-Reranker-2B。实际文档编码在 CPU / BF16 上执行，没有更换模型。聊天保持 `gpt-5.6-luna` / `gpt-5.6-terra`。 |

## 已通过

### KB-03：实际阻塞边界修复

MinIO 使用原 SDK 的独立 OkHttp 请求配置及共享连接池，每次 stat/get/put/delete 计算当前编译剩余时间，整次调用包括响应体受期限约束，EOF / 异常复核原 authority。Milvus 使用 SDK 自带的 shaded gRPC Context，以原 60 秒和当前剩余时间的较小值发起请求；不调用会修改共享配置的 `withTimeout`。JDBC 捕获、检查、发布采用原 Spring 事务定义的单次副本及剩余期限，不递归执行设置期限所用事务的自身 authority。

实际回归：4 个 loopback HTTP + MinIO SDK、3 个 loopback gRPC + Milvus SDK、8 个预算测试通过。真实 PostgreSQL 来源行锁探针 8/8 通过：capture/check/publish 分别在 1102/1155/1113 ms 收到 SQLSTATE 57014 的取消；没有半成品输入或发布写入，共享事务配置及原任务 attempt / epoch / 600 秒 deadline 不变。500 ms 嵌套观察期限只用于组件探针，JDBC 按秒向上取整；它不是修改业务租约。

真实 PG schema `evokb_jdbc_budget_1cd7ad584e05`、初始化 SQL 和全部观察保留。实现及未覆盖边界见 [编译 I/O 期限](compilation-io-deadline.md)。

### KB-01 / KB-04：真实页面上传和跨组件读回

本批三份关联资料均由正常浏览器文件选择、资料信息确认、MinIO 分片上传及后台 Raw 处理完成，使用真实 Qwen 编码，均第一次尝试成功。每份的 PG 原权限、owner/epoch、物理 MinIO 原件 Hash、完整 Milvus 集合、1024 维单位向量与编码缓存字节、V23 pipeline/checkpoint、Redis 原始 delivery 及终态 ACK 独立读回 23 项通过，共 69 项；不是空库、Mock 或改库成功。

| 资料 | 实际版本 | 文件 ID | 执行产物 |
|---|---|---|---|
| 青禾设备采购合同 | `75782b74-0580-49af-8ea5-a4e3bad22102` | `f3ad23b7-dfd0-4864-882a-11efc30fd031` | 第一批 cc192662 |
| 青禾设备采购补充协议 | `32a185cf-c840-4be7-9df9-9b933c1470fb` | `c8445f96-0345-45aa-abde-b1ba2ec43b3d` | 第一批 cc192662 |
| 青禾设备验收说明 | `df9174e1-c39f-44f5-876d-e19a17049f8d` | `afd39ad3-e896-4566-88dc-1afd277900de` | 最终 bc8c9e49 |

第三份最终产物上的单段真实编码为 134 token，服务日志观察 71.16 秒；正常页面最终显示“已完成 / 第 1 次处理”。没有用旧文件的上传时间冒充新 JAR 执行。

可重跑的三份合成原件、操作步骤和只读 SQL 在 [本批资料](../../deploy/acceptance/20261102/README.md)。本次 `readback.sql` 已实际执行两轮，无写操作。

### KB-04：一条限定来源的真实问答

会话 `263a3f24-73fc-4c3f-845c-eb7bd532f116` 使用自然语言询问 HT-2026-1102 补充协议的尾款期限、前提、总价及质保，正常页面将来源部门选为“验收实验室”。真实结果为 ANSWERED：45 日、书面验收与合法有效发票两前提、120 万元及 24 个月不变，且不能推断已经验收/开票/付款。4 条引用均来自本批合同或补充协议；实际打开引用侧栏核对了原文，PG 会话持久结果一致。

它是开发验收的一条成功案例，不是完整正式题集，也不是下方无来源范围失败的同条件复测通过。

### KB-02：两例真实 Luna 审计诊断

固定旧版本 `4b2b715d-196a-492f-972a-3994963d26e6` 的候选审计组件诊断 2/2 通过：主体颠倒改写被拒绝、精确原文正例被接受，分别观察 74.785 / 40.410 秒。调用实际既定模型，保留 7 维审计输出；该诊断只读、不发布、不重写旧错误事实，不等于新 Summary 生成质量已通过。

### 解析服务的真实超时后恢复

固定 PDF 自然 600 秒超时后，HTTP / SQLite 均保留 `PARSER_STAGE_TIMEOUT`，旧 worker 已退出。随后通过正常 HTTP 提交保留的合成 DOCX，新的 native Office worker 完成解析；来源事实、表格跨度、原件字节及 SQLite 账本与 HTTP 一致，5 项检查通过，原 600 秒配置及 bundle 不变。该恢复没有运行扫描 PDF 模型，也不是 PDF 内容质量成功。

## 实际失败

1. **KB-01 扫描及跨页 PDF 尚未通过。** 同一原件 SHA `aba0b5bbf65e49098cddc6ab2ea166e7ff2c975abed68d714c1efacde1bac471`，155311 字节、两页、原 Gold 不变。第一次实际 native 68.3 秒完成，但仅 14/15 内容检查通过，扫描行 HT-SCAN-703 的条件多出 `atceptance`；没有特判单词或改 Gold。第二次相同配置在 600 秒自然超时，观察 603.354 秒，内容和视图检查未到达；任务 `b170d226-a7cf-4b4e-b1a7-e93be42943e6` 保留。14/15 不能记通过。
2. **KB-02 本批 GUI Summary 未发布。** 正常点击生成知识的任务 `5c9895d2-c7a2-485a-9025-6343e5f967a4` 自然完成五次尝试并 FAILED。第 1、2、4 次为审计 claimPart 不在候选 OBJECT/ACTION/SCOPE 原文中，第 3 次为远端 SSL 握手中断，第 5 次为 STAGE_DEADLINE_EXPIRED。历史及 `summaryCount=0` 实际读回；截至这批结束没有手动第六次、重置或绕过审计；后续自动 repair 的越额缺陷、修复与产物隔离另见最后发布记录。
3. **KB-04 不限定来源的首轮真实查询失败。** 会话 `a277ef62-989e-4ebb-8b5e-edeea804d34e` 在重排后遇到 embedding 503，终态 FAILED，展示依赖不可用并保留原文，没有伪造答案。随后选择来源部门的成功会话是不同输入条件，原失败保留。原已知问题是客户端重排等待结束并不保证本地 kernel 已结束，后续编码可能受影响；本批未改检索超时或模型服务身份。

## 未测与剩余边界

- KB-03 的 Milvus 迭代器 close RPC、JDBC 池获取/网络分区/继承外层事务以及 Raw 非编译作用域，未在本批全部证明受同一期限强制取消；完整编号仍未关闭。
- 新 Summary / Concept / Graph / Wiki 真实发布和实际恢复未全部通过；旧已通过组件结果不替代新模型产物。
- 正式固定问答大集、留出复测、100 文档真实模型 Hash 收益、实际午夜调度及其它等时任务未在本批完整重跑。
- 本批没有“缺少模型凭据”的受阻项；上述模型输出、网络/推理及 PDF 问题属于实际失败，剩余矩阵属于未测，均未算通过。

## 实际命令及私有证据

仓库根目录执行，报告必须使用新文件名：

```sh
python3 scripts/verify-backend.py --skip-format \
  --output .tmp/acceptance-20261102/backend-verify-3.json
python3 scripts/test-postgres-compilation-jdbc-deadline.py \
  --jar-path java/ekb-bootstrap/target/evokb.jar \
  --output .tmp/acceptance-20261102/jdbc-lock-deadline-1.json
python3 scripts/deploy-target.py check \
  --verification .tmp/acceptance-20261102/backend-verify-3.json
python3 scripts/deploy-target.py stop
python3 scripts/deploy-target.py start \
  --verification .tmp/acceptance-20261102/backend-verify-3.json
python3 scripts/test-raw-publication-readback.py \
  --logical-name '青禾设备验收说明' --parser-name local-text --parser-version 2 \
  --require-current-checkpoint --version-id df9174e1-c39f-44f5-876d-e19a17049f8d \
  --file-id afd39ad3-e896-4566-88dc-1afd277900de \
  --original deploy/acceptance/20261102/青禾设备验收说明.md \
  --output .tmp/acceptance-20261102/notes-raw-readback-1.json
python3 scripts/test-mineru-after-timeout.py --workspace .tmp/mineru-service \
  --fixture .tmp/mineru-service/1df331b9-ba29-46e0-ba69-693fdec4fca3/original \
  --failed-task b170d226-a7cf-4b4e-b1a7-e93be42943e6 \
  --output .tmp/acceptance-20261102/parser-office-recovery-1
```

上面是实际执行过的文件名，再运行时需换新报告。停止服务前还须重新确认本环境无在途任务，不是复制命令即可无条件终止。完整测试的 smoke 跳过原因、历史夹具编译/Connect 握手错误、被“新报告名”保护拒绝的一次启动、所有原失败、JAR/源码清单、正常发布回执、PG 初始化 SQL、模型输出、HTTP/Redis/Milvus/MinIO 读回、截图及 AX 文本均在被 Git 忽略的 `.tmp/acceptance-20261102/`；账号和令牌未写入本文件。
