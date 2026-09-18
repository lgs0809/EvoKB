# 固定候选的 Grounding Judge 协议

> 后续规则更新：本文中旧五次停止的运行事实为历史记录；2026-11-02用户改为 [后台持续指数退避](durable-background-retry.md)。七维事实审计、单次600秒及原epoch权限没有放松。


2026-11-02：Summary / Wiki 的模型输出协议改为 `FULL_CANDIDATE_V2`。模型返回七维 finding、证据原文引文及 reason，服务端固定候选 ID 和完整命题，再调用既有事实审计。没有把一次不合格的旧 claimPart 改写后当作合格输出，也没有手动增加重试次数；后来发现的自动 repair 同输入越额缺陷已由任务族配额和历史叶隔离修复，见下文及最后发布记录。

## 为什么分开输入绑定和语义判断

旧协议让模型同时解释主体、动作、范围等语义，又复制一个必须逐字存在于候选中的 claimPart。模型把角色解释或改写填入该字段时，即使 JSON 完整，也会触发形状拒绝，使整次生成走持久任务重试。旧采购合同任务的第 1、2、4 次正是在 OBJECT / ACTION / SCOPE 的这个边界失败；它的完整响应当时没有持久化，不能恢复或冒称已经知道具体错误文本。第 3 次是 TLS 握手失败，第 5 次到达原阶段期限。

新协议让模型判断不可变的整个候选，避免要求它再生成输入身份。适用维度的 claimPart 由服务端绑定为完整候选，不适用项仍为空。**完整候选绑定不是对主体、动作或对象的精确局部跨度定位。** 存储审计明确记录 `claim_binding=FULL_CANDIDATE_V2`；既有合法局部引文证明继续标为 `MODEL_QUOTED_SPAN_V1`，不被重新命名或补造。

`grounding-constraints-v1` 仍表示原有语义审计规则，规则本身没有放宽。新 wire DTO 和存储 binding 各有明确版本；缺失/未知的新协议版本会拒绝。

## 不变的拒绝边界

- 必须恰好有 SUBJECT / ACTION / OBJECT / POLARITY / CONDITIONS / SCOPE / TIME 七项，每个适用项都判断完整候选的全部分句。
- 任一 CONTRADICTED 或 UNKNOWN 都不能被总体 `supported=true` 覆盖；证据不足不能依靠常识补齐。
- SUPPORTED / CONTRADICTED 的每条引文仍须出现在本次给定证据中；不替换、模糊匹配或自动编造引文。
- SUBJECT、ACTION、POLARITY 不能为 NOT_APPLICABLE；不适用项不能携带引文。模型仍须判断原命题和候选均无该维度才可不适用。
- 缺项、重复项、null、额外/缺失/重复 candidate ID、错误协议版本、错误引文都拒绝；缓存证明继续经过原完整输入和事实审计。
- 600 秒、原 owner/epoch、全部来源权限及最多五次业务尝试保持不变。旧五次失败任务没有重置，新验收使用从未生成 Summary 的不同来源版本。

## 输出收据

远端适配器在结构化解码和后续语义审计前，将已收到文本写入既有 `audit_event`。因此坏 JSON、错误引文或否定的语义判断也有收据；不能把 `COMPILATION_MODEL_OUTPUT / SUCCESS` 误读为生成成功，`phase=RECEIVED_BEFORE_SCHEMA_AND_SEMANTIC_VALIDATION` 明确表示这里只确认收到响应。实际任务结果及同 attempt 的失败历史独立保留。

每条收据保留原 task / attempt / executionEpoch、模型角色和身份、调用用途、原文 UTF-8 字节数、完整 SHA-256 及最多 64 KiB 的有效 UTF-8 前缀。超限明确标记 `responseTruncated=true`，不宣称保存了完整输出。没有保存 HTTP Header、密钥、配置对象或系统代理信息；无响应的传输故障只记异常类型，不记录可能包含账户信息的异常消息。

收据使用单独、固定五秒 Spring 事务写入，不能发布知识资产或续约原任务；来源计数与收据一起提交。临时数据库故障按原有依赖失败策略处理。写入事务内 statement 受期限约束，连接池获取/网络分区仍不能因此宣称已经全部强制终止。

审计读取在 count / 分页前检查**收据所属原 epoch 的全部输入**，要求完整计数、当前 ACL 与权限都匹配。不能用最新一次尝试的公开输入，去展示前一次秘密来源的响应。来源撤权、输入丢失、未绑定或错误类型的元数据都隐藏，不扩大权限。

## 本批实际验证

目标回归 93 项通过，含框架 schema、原七维拒绝、全候选/旧局部证明 JSON 兼容、坏 JSON 在实际 HTTP 解码前留存、UTF-8 边界、凭据不入收据以及原持久工作流。首个 schema 检查先在旧代码失败，旧日志保留。

真实 PostgreSQL 收据与原 epoch 权限 9 项通过；分别保留源类覆盖的组件报告和最终 `38b57794` JAR 的原生报告，不互换身份。新独立语义夹具的真实 Luna 诊断 6/6 通过：正例、主体互换、否定、条件遗漏、且改或、虚构时间。Gold 在调用前冻结且未发给模型；这是候选代码的只读诊断，不是旧任务第六次尝试或完整生成。

完整门禁、正常部署和新独立 Summary 的实际结果见本批验收记录；原 PDF、旧 FAILED5 及正式大集未测范围不随协议修复自动变成通过。手动 SQL 为 `deploy/acceptance/20261102/model-audit-readback.sql`，私有原输出及运行证据在 `.tmp/acceptance-20261102/`。

## 任务族配额、当前可用性与产物绑定

`FULL_CANDIDATE_V2` 是 wire / 绑定协议版本，语义 policy 仍为 `grounding-constraints-v1`，不自动给旧 FAILED5 解禁。同知识库、来源版本、语义 policy 与实际 ACL 的 Summary 总次数由 `SummaryTaskFamily` 在入队、retry、claim 和发布守卫统一限制，普通 / repair / command 名称不能增加预算；真实 ACL 或内容版本变化仍区分新输入。历史自动 repair 曾绕过单 task 上限，第六次的叶产物已由应用正常 STALE / STAGED 隔离，保留全部记录，不计通过。

目前没有单纯升级 JAR、换 profile 或重命名即可重跑旧 FAILED5 的入口；同原件 SHA 与同 parser pipeline 的重复重解析复用原版本。不得为避开次数制造新来源或权限变化。

新独立 Summary 的真实生产者是38b57794，原 Concept 正常第二次成功的生产者是4748e9f0，最后发布 / 只读核验是9aeeafd4；事实与原模型审计存协议、task、epoch和来源计数，JAR身份通过独立不可变部署回执、进程出生及任务时间交叉绑定。审计数据库没有 JAR SHA 内建签名列，不宣称密码学的原生产者证明。新条目在正常关联旧来源后进入STALE，历史生成成功不能替代当前完整来源门槛。完整状态、实际越额原因与复测见 [最后发布记录](2026-11-02-closure.md)。
