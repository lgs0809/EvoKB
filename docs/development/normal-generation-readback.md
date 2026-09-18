# 正常重新生成的只读验收

从页面的“生成知识”入口发起正常任务，观察实际 Summary 和自动排队的 Concept 任务。数据库中旧任务、旧摘要、失败记录及旧条目都保留。新版本验收必须绑定这次正常生成的实际 Concept `task_id`，不能挑一条历史成功任务替代失败的新任务。

```sh
python3 scripts/observe-target-stable-entry.py \
  --version SOURCE_VERSION_UUID --output /private/evidence/new-observation.json

python3 scripts/test-target-stable-entry-publication.py \
  --version SOURCE_VERSION_UUID \
  --execution-proof /private/evidence/new-observation.json \
  --concept-task ACTUAL_NEW_CONCEPT_TASK_UUID \
  --output /private/evidence/new-publication.json
```

所有输出路径必须全新。观察脚本读取完整历史并标为 `OBSERVED_NATIVE_STATE`，不声称验收通过。读回脚本要求指定任务真实成功、来源一致、绑定完整 Summary/ACL/content，以及观察时的进程和当前不可变产物一致。未完成或失败的新任务会留下失败报告，流程不会回退选择旧成功。

实际读回沿现有框架核对当前审计策略、Summary 原文证据、任务输入及输出哈希、当前权限和执行期限；独立读取 PostgreSQL、MinIO 全字节哈希和 Milvus 实际向量。仅检查该新任务真正发布的 Concept 条目，同时列出其他旧条目。未重建的旧条目以及尚未升阶生成的 Wiki/Graph，不计为本次新产物。对象读回在数据库事务外进行，过程不调用推理、不改变业务状态，也不产生合成向量。

默认不传 `--concept-task` 时保留原“首次生成只有一条成功 Concept”验收契约，旧结果和旧命令不改写。

```sh
python3 scripts/test-stable-entry-readback-binding.py \
  --output /private/evidence/reader-binding-selfcheck.json
```

此命令只是合成观察记录的选择门禁测试，不等同真实模型、页面或物理资源验收，不计入后端完整回归数量。

## 正常 Graph / Wiki 产物

同样从正常页面或原有修复队列取得实际 Graph/Wiki 任务。保留观察记录中的全部历史，绑定当前稳定条目身份与精确 revision；旧任务、旧未认证产物或已经变化的条目都不能作为这次任务的替代结果。Graph/Wiki 的任务身份还要求 `complete-inputs-v1`，Summary/Concept 的任务身份仍按既有 grounding 策略。

```sh
python3 scripts/test-target-stable-derived-publication.py \
  --version SOURCE_VERSION_UUID --kind GRAPH --task ACTUAL_NEW_GRAPH_TASK_UUID \
  --query ACTUAL_NORMAL_GRAPH_BROWSE_QUERY \
  --execution-proof /private/evidence/new-observation.json \
  --output /private/evidence/new-graph-publication.json

python3 scripts/test-target-stable-derived-publication.py \
  --version SOURCE_VERSION_UUID --kind WIKI --task ACTUAL_NEW_WIKI_TASK_UUID \
  --execution-proof /private/evidence/new-observation.json \
  --output /private/evidence/new-wiki-publication.json
```

只读读回核对原始资料及 Summary 的 MinIO 全字节哈希、当前逐项审计、当前来源与 ACL、真实任务输出哈希和 PG 物理产物。Graph 要求有实际业务关系、完整输入 revision 和有效证据，并将正常接口与各自权限下的原生浏览结果比较。Wiki 同时核对正文、逐项支持事实、完整输入依赖、当前物理资产 revision，以及 Milvus 的真实 1024 维向量、哈希和证据集合。管理员、低权限账户和匿名接口独立验证，内部审计原文不得出现在低权限 DTO。

这些命令不触发模型、不生成合成向量、不修改任务状态。校验失败保留报告，不改旧 PASS 或挑选其他历史成功。读回只能证实实际发布及证据边界，不能称为对文本语义的形式证明；尚未实际发起和读回的新任务仍列未测。
