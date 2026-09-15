# 2026-11-02 本地人工验收资料

> 最新后续：用户已将旧五次停止改为后台持续指数退避。当前运行与实际同任务第6次成功、5条目专用范围证明重建见[后续验收](../../../docs/development/2026-11-02-durable-retry-acceptance.md)。手工核对使用[durable-retry-readback.sql](durable-retry-readback.sql)，旧规则下的SQL/失败仍作历史证据保留。


合同与补充协议两份 Markdown 是虚构采购资料，只用于隔离本地环境。原合同约定双条件满足后 30 日内支付；补充协议将期限调整为 45 日，同时保留书面验收和合法有效发票前提。总价 120 万元、质保期 24 个月不变，协议不证明已经验收、开票或付款。

第三份 `青禾设备验收说明.md` 是当时 bc8c9e49 发布后新上传的关联说明，明确没有登记验收、收票或付款完成；其作用是正常新上传和运行产物绑定，不能替代原问答的同条件重跑。

## 重跑正常流程

1. 按 `deploy/target/README.md` 启动现存依赖和经过完整构建验证的后端，打开 `http://127.0.0.1:18481/enterprise/ingestion`。使用既有本地账户；账号文件不加入 Git。
2. 在正常页面上传本目录两份文件，来源部门填写“验收实验室”，文档类型填写“采购合同”，分类填写“合同与补充协议”，标签填写 `HT-2026-1102`。密级遵循当前用户权限。
3. 等待“处理成功”，打开资料库核对原文。已有同名资料的重复导入由应用原有内容 Hash / 版本规则处理，不用 SQL 覆盖原始资料。如果需要独立观察新的上传执行，复制为新的合成合同编号和逻辑名称；不要冒充旧记录在新 JAR 上重新执行过。
4. 用自然语言查询：

   > 青禾实验室的 HT-2026-1102 设备采购合同，补充协议生效后尾款应在几天内支付？还需要满足哪些条件，总价和质保期有没有改变？请分别给出原合同和补充协议的依据。

5. 可以在“知识范围”选择“验收实验室”，打开回答的每一条参考资料核对原文。来源范围是本轮输入的一部分；改变范围后的成功不覆盖原无范围失败结果。
6. 从资料详情正常点击“生成知识”。观察真实任务及失败历史，禁止直接改库制造 Summary、Concept、Graph 或 Wiki 成功，技术失败保留原任务和尝试历史，后台持续指数退避重试；不足以形成有效结论的业务结果不能冒充技术失败或成功。

## 只读核对

本目录 `readback.sql` 只读本批三份资料的 PG 版本、原件上传、Raw 发布、编译任务及事实审计：

```sh
docker exec -i evokb-target-postgres-1 psql \
  -U evokb_target -d evokb_target -X < deploy/acceptance/20261102/readback.sql
```

跨 MinIO / Milvus / Redis 的验证采用现有 `scripts/test-raw-publication-readback.py`，必须传实际版本、文件及原件路径。每次使用新的报告文件，不能覆盖旧失败：

```sh
python3 scripts/test-raw-publication-readback.py \
  --logical-name '青禾设备采购合同' --parser-name local-text --parser-version 2 \
  --require-current-checkpoint \
  --version-id '<本次实际版本>' --file-id '<本次实际文件>' \
  --original deploy/acceptance/20261102/青禾设备采购合同.md \
  --output .tmp/acceptance-manual/contract-raw-readback-01.json
```

脚本还会核对当前冻结运行产物与文件执行时间。重启后不要将旧 JAR 产生的文件重新记作新 JAR 上传验证通过。全部私有报告、原失败、截图、初始化 SQL、编译日志及模型诊断位于 `.tmp/acceptance-20261102/`，默认忽略提交；仓库中不保存密码、令牌和个人原件。

本批最终通过、失败、自动关联后当前条目 STALE 及旧FAILED5不可直接再生的范围见 `docs/development/2026-11-02-closure.md`；首批历史见 `2026-11-02-acceptance.md`。新增 `summary-family-readback.sql` 可只读核对实际旧任务族、五次失败、越额叶隔离和独立成功 Summary；不要用 SQL 重置次数。上述步骤可重跑，但并不保证真实模型永远返回合格内容。

两份旧NULL管线原件的后续正常重新解析见[11月3日验收](../../../docs/development/2026-11-03-legacy-reparse-acceptance.md)，配套只读SQL为[legacy-reparse-readback.sql](legacy-reparse-readback.sql)。原FAILED6及旧NULL身份保留，现有v2各23项读回与页面幂等复用另列。
