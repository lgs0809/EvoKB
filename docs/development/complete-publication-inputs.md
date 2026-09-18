# 完整输入与知识产物权限

Wiki 正文引用、图谱关系证据和模型完整输入是三个不同集合。模型可能读取某份资料后没有引用其中的事实，但该资料仍然影响候选身份、标题或合并决策。权限、缓存复用和失效必须检查完整输入；页面引用继续只展示真正支持该结论的证据。

`CompilationContracts.COMPLETE_INPUT_POLICY` 是统一的 `complete-inputs-v1` 身份。正常编译先固定来源与 ACL 快照，在既有任务所有权、执行期限和来源锁的短事务内发布。Wiki 保存稳定条目的全部输入摘要。图谱编译和 Wiki 关键词投影显式传递全部冻结来源，原生图谱 revision hash 同时包含证据和完整输入 ACL。

V22 只增加认证字段、来源数和图谱输入表，不修改历史 PASS、Gold、任务状态或热度。新 Wiki、图谱关系及 Wiki 物理索引保存该策略与实际来源数；读取时还要验证非空快照、来源数一致、全部现行 ACL、有效来源，以及引用必须属于该快照。`SourceAccessSql` 统一用于列表、计数、正文/下载、图谱浏览与图谱扩展，检查发生在分页和 limit 之前。派生搜索的原生资产权威也拒绝旧 Wiki revision 和没有完整输入 revision 的 Wiki 投影。

任务列表、搜索、失败详情、Dashboard、资料详情计数及质量问题使用同一个 `compilationTask` 权威条件。当前执行轮次 `execution_epoch` 已捕获的每份输入都要满足当前权限及 ACL；此前轮次的输入不替代当前轮次。只含公开输入的任务与尚未捕获输入的排队任务保持原来的可见范围。失败及 `RETRY_WAIT` 的错误信息也不能通过公开主来源泄露受限的其他输入。

缺少认证的旧产物保留历史，通过正常生成流程重建。正文相同、历史任务成功或当前条目同名均不证明某个旧产物版本的完整输入，不能据此回填认证。GRAPH/WIKI 的任务幂等身份包含该策略，因此旧成功不会阻挡正常重建；SUMMARY/CONCEPT 的既有身份和索引 hash 保持原规则。该策略证明权限依赖边界，事实正确性仍由独立的当前 grounding audit 检查。

## 可重跑组件验证

使用新的输出文件名，每次失败、种子 SQL、日志和独立数据库 schema 都保留。组件数据明确标为 synthetic，不代表真实模型、浏览器或 MinIO/Milvus 端到端通过。

```sh
python3 scripts/test-grounding-focused-junit.py \
  --jar <已验证不可变依赖JAR> --include-compilation --output <新报告.json>

python3 scripts/test-postgres-grounding-audit.py \
  --jar-path <不可变pre-V21依赖JAR> \
  --classes-path <聚焦报告.frozen/classes> --output <新报告.json>

python3 scripts/test-postgres-complete-inputs.py \
  --jar-path <不可变pre-V22依赖JAR> \
  --classes-path <聚焦报告.frozen/classes> \
  --candidate-migration java/ekb-bootstrap/src/main/resources/db/migration/V22__complete_publication_inputs.sql \
  --output <新报告.json>

# 独立保留旧不可变 JAR 上的任务权限反例（预期失败）。
python3 scripts/test-postgres-task-input-authority.py \
  --jar-path <原pre-fix不可变JAR> --output <原失败报告.json>
```

完整构建后的 JAR 已带 V22 时，最后一个命令省略 `--classes-path` 和 `--candidate-migration`。测试覆盖未引用受限输入、现行 ACL 撤权、缺失依赖行、旧 NULL 认证、公开产物正常读取、旧任务身份保留、新任务正常重建和来源失效。完整 Maven、正常部署及实际模型链另行保留证据，不能用上述组件结果替代。
