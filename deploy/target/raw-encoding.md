# 原件切块与实际向量缓存验收

使用实际 Embedding 模型 `Qwen/Qwen3-VL-Embedding-2B` 的本机现有 tokenizer snapshot；模型 revision/tokenizer revision 均为 `9f2f7e710d6d81056aa5c0a4f04764fec6bb7bda`。复制 6 个 tokenizer/预处理文件及 SHA256，不复制模型权重。原生 DJL 0.32.0 与服务 token-count 的六条输入逐条一致 `[37,44,36,66,800,20]`，包含实际模板和 EOS；不以字符长度作兜底。

默认结构目标 600、硬上限 900、同连续正文重叠最多 80 token，完整 600–900 自然段保留。表格重复表头/单位/条件，数据行不重叠，保存合并单元格的原行/原列身份。末尾不足 150 token 只在同标题、同结构且非独立条款时合并。原文内部空格、数字、大小写保留；仅换行与边缘空白规范化。

未配置本机 manifest 不影响查询/前端启动，但入库明确 TOKENIZER_UNAVAILABLE。完整缓存身份包含模型、实际指定 revision、维度、6 份 tokenizer/模板配置和 native rendering 身份、指令、完整预处理协议、端点；未知模型无静默回退。缓存是计算结果，不携带 ACL 或发布成功。不能凭本地 manifest 宣称服务 ready 已核验运行时 revision；后续共享服务已有同次编码响应的atomic身份合同，但其真实验证与产品缓存接入须分开看待。

## 可以重跑的初始化与正常流程

```sh
python3 scripts/prepare-raw-cache-fixtures.py --output .tmp/manual-raw-cache-fixtures
python3 scripts/test-real-raw-cache.py \
  --fixtures .tmp/manual-raw-cache-fixtures/manifest.json \
  --output .tmp/manual-raw-cache-acceptance-$(date +%Y%m%dT%H%M%S)
```

初始化只创建标记合成数据的 DOCX：V1（60 日）→ 同一 V1 → V2（30 日）→ V3（改全局标题）。相同逻辑名联系三个版本；每个版本在前一个正常任务成功后才导入，不能直接 SQL 造成功。已有 fixture manifest 会核验原件字节且不覆盖。新输出目录必须唯一；测试保留每个 batch/file/version ID、状态变化、共享访问日志及 PostgreSQL/Milvus 读回。

需要当前本机已存在的 `.tmp/target/accounts.json`、`.env.target` 与 `.tmp/target/process.json`；探针不给控制台/回执输出密码。运行地址 `http://127.0.0.1:18481`，页面可通过上传批次逐个导入相同原件进行手动复验。脚本使用正常身份登录、元数据修改、入队和执行 API，无直接写业务数据库。另有只读 SQL `deploy/target/raw-encoding-readback.sql`。

只读 Java 探针仅针对 `.tmp/target/process.json` 所指不可变 JAR 编译，核验 JAR SHA，重建实际模型输入计算缓存键，检查 1024 维非零有限值、token 上限、新版本 evidence 和当前 Milvus 投影。不会编译可变 production 源或调用 Embedding。`raw_vector_cache` 无源 ID 列，所以仅凭行数/创建时间范围不能推断命中；必须派生完整键。

## 实际结果与边界

2026-10-03，部署 JAR `2190e4bdb4b15101afa5b4ee53acec5e9a159be9a8a09636f11b248d20d275a0`：

- 首次正常 API 实际解析、Qwen 编码、入库成功，3 个完整输入写缓存。首轮独立探针因 Java 的系统代理未绕过本地 PG 失败；原报告保留 FAIL，修探针本机直连参数后重跑。
- 正常 API 缓存验收第二轮 8 / 8 PASS：同原件复用版本；仅改付款条件，未改条款的 exact cache key 和创建时间不变；只改标题，所有完整输入键不同，正文相同也不能复用；三个版本由正常链路生成，最终仅 V3 ACTIVE。
- 四轮实际 PostgreSQL / Milvus / 原生 tokenizer 读回均 5 / 5 PASS。真实 1024 维向量与原件版本证据关系一致。
- 另外独立 PG 缓存组件实验 9 / 9 PASS，覆盖并发不可变写、坏向量、完整 profile 失效；该组件用了合成向量，不能当真实模型编码。真实模型证明来自上述 API 批次。
- 共享 Qwen HTTP 访问日志记录的是全局请求，可能包含其他后台任务；没有把其次数冒充本任务精确调用次数。严格组件级 counting decorator 可另测，实际底层仍需 Qwen。

此处的 `2190e4…` API 缓存批次没有覆盖浏览器、PDF hybrid 或 Redis Stream/PEL/600 秒恢复；该历史构建当时还会自动提交旧 Summary 链路。不能把它当成后续选择性生成的证明。后续 Raw owner/epoch、正常 PDF/DOCX 浏览器链路与分片上传已另批实施，逐项证据和版本边界见 [原件任务与分片上传](raw-task-and-multipart.md)。

## 同次响应的实际编码身份

共享Qwen服务后来增加`encoding_identity`：完整profile包括实际loaded revision、native tokenizer/preprocessor/pipeline SHA、packages、device、CPU算术与线程策略、原权重dtype、input type、输出维度与完整输入/float32向量SHA。当前`3854403e…`产品的Raw embedding DTO还只保留`data`，原缓存profile仍由冻结manifest＋本地有效配置生成，不能把新服务真实认证通过说成产品已验证所有动态计算身份或给旧向量补证。当前CPU `expanded-kv-fp32`保持原模型revision、BF16权重及原输入模板，这是精确算术/pipeline身份缺口，不是已证实的模型或模板替换；cache hit仍受独立owner/epoch/600与source ACL短发布事务约束。

只读审计保留实际JAR classes与full15源SHA匹配、六资产SHA及正常PG有效模型配置（不含凭据），可重跑：

```sh
python3 scripts/audit-production-raw-encoding-identity.py \
  --verification /absolute/path/entry-raw-backend-verify-15.json \
  --actual-encoding-response /absolute/path/raw-hash-efficiency-real-subset-1.preflight.json \
  --output .tmp/manual-raw-profile-audit-new.json
```

实验runner已单独加动态身份守卫：真实预检与每个响应都核对完整input和float32 vector SHA，profile变化明确拒绝并保存实际HTTP200的成本，实验key绑定这份真实profile。固定两份DEV资料、baseline＋五档比例、两方案实际24/24来源执行通过，22次benchmark HTTP/57向量＋独立1次预检/1向量，所有模型/持久组件读回通过。全量与Hash编码36/21项，总处理耗时452.605/451.768秒几乎相同；不声称显著速度收益、正式100份已执行或问答质量通过。完整证据、原失败和重跑步骤见[固定切块与Hash实验](chunk-and-hash-experiments.md)。


2026-10-03 后续普通正确性批新增严格原子身份接线，已通过source-stable完整回归：283项、282通过、1显式模型跳过、0失败。新不可变JAR `e9d63bbcad4670132a2c7beef07bde87ed451a4728e4250f8362af63469a7366`于12:44:44Z正常部署。正常原件级实际认证/缓存验收另待完成，不能与历史 `3854403e…` 证据混称。生产四参数 `RawVectorEncoder(..., true)` 仅按实际驻留认证的完整 runtime profile 查询新缓存键；旧 local-only 键、旧 raw-v2 checkpoint 全部保留并旁路。GET `/v1/embedding-identity` 的503/404代表当前未知，不加载模型、不消耗推理重试。新的同次响应证书核对 canonical profile SHA、model/revision、完整 UTF8 input SHA 和每个输出 little-endian float32 SHA，维度/有限数值/归一化及实际预处理/框架/CPU策略共同进入身份。未知响应可以产生新向量，但不能缓存；同一 Session 曾用认证缓存后不能混入未知新批，反向混用也拒绝。未知 checkpoint 使用本次 nonce，不删历史块或重置业务尝试。

`raw-atomic-junit-3.json` 为128MiB/2CPU的42项实际 JDK/JUnit/loopback HTTP 定向验证，模型调用零。包括跨批驱逐、身份变化、拆批逐输入身份、哈希/维度/顺序错误、认证错误和旧 checkpoint 旁路，并从已保存的真实 Python Qwen HTTP 请求/响应字节重放 Java canonical/Float32 校验；重放不是新推理或新上线验收。第一轮 record 编译缺陷保留，旧39项snapshot不被新42项覆写。新增共用 Spring Retry 2.0.12 有界 backpressure 仍待本批最后冻结：最多5次、指数退避和总配置观察期限共同限制；过期不再开额外 timeout 窗口，600秒阶段和5次业务尝试保持原值。认证、结构和身份错误不重试。

旧 `raw-production-encoding-identity-audit-1.json` 中“WebClient”称谓错误已披露在独立 `.errata.json`；实际源码一直是 JDK HttpClient + Jackson，未为换框架扩大改造。旧报告和此前未保留 atomic 字段的部署事实仍保留。


该批最终 `raw-atomic-junit-6.json` 实际48/48定向回归通过、0模型。额外真实慢正文 Red/Green：`raw-http-slow-body-1.json` 的200ms配置在服务器先返回200headers和8字节后，700ms拖尾导致JDK同步byteArray读取实际860.651ms才成功，明确FAIL；原输入与失败未改。改用JDK标准sendAsync及完整响应future的remaining-deadline等待/cancel后，同fixture实际302.390ms（包括约100ms创建HttpClient）以typed观察期限失败退出，未返回不完整/超期数据，1请求，两个断言通过。200headers不再结束总正文观察期限。SpringRetry仍最多5次，在原embedding/rerank配置总预算内指数退避，过期不新开额外窗口；原超时后扇出行为因此改为有界失败并有独立回归。完整后端验证/生产普通原件cache验收由新不可变JAR另绑，不把本定向overlay当已上线。
