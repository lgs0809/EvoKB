# 两份旧未知管线资料的正常重新解析

本批只沿既有流程处理F03财务登记/F04采购合同两个旧文件，不自动迁移其余历史资料。后端保持 `ef29c903d74b4bb541db0f1c8b41f84f95aef053650e8c4d41c4162bc8558b89` / PID93814 / 原00:11:45进程，未改后端源码、期限、模型或重跑大门禁；后端仍对应原384项（383通过、1显式环境smoke跳过、0失败）。原[后台退避验收](2026-11-02-durable-retry-acceptance.md)及失败记录保留。

## 原件、原任务和新产物

先实际GET MinIO的original对象，而非读取parsed块拼文件。F03原件445字节，完整SHA `d3a7c60e279ebe58b9104024c0dbe2ae8dba72f799df42dcf06bd11a841c64f7`；F04原件804字节，SHA `c2d587aef61782b561caa64435f404793e6ea7a480765222a7a692524f6b36c3`。两者均与PG原始记录一致；独立保留原字节在私有证据目录，未覆盖原对象。

| 资料 | 保留的旧记录 | 实际正常新版本 |
|---|---|---|
| F03财务登记 | file047424ea… / version607b1e4f… v1，FAILED6，原parser/claimed/checkpoint身份仍NULL，6个旧raw块保留 | v2 `d71db67a-7e49-48a2-a4e3-be334546e94f`，file `302cc5fd-92b8-4afc-86b0-66227d4d85f6`；00:31:57→00:34:18，attempt1成功，140.6秒 |
| F04采购合同 | fileaf87c189… / version6125901a… v1，FAILED6，原身份仍NULL，10个旧raw块保留 | v2 `a4f18da3-81b9-406d-b996-b062d11d293c`，file `2640bc3d-c2f3-4c3b-8533-51157427ffee`；00:35:03→00:36:37，attempt1成功，93.2秒 |

两新版本均通过既有管理员document/reparse服务真实处理，reparse_from_version_id指向各自旧版本，冻结当前完整pipeline `5e1b8b13533c0fbaf792bb40f37b3e13d544ef2ad41c2b525c9630ef0a76a369`。未给旧行补当前SHA，未清零旧attempt、旧失败或替换原件，也未把旧parsed结果冒充新的正常解析。实际使用local-text/2、真实原Qwen3-VL-Embedding-2B请求1024维；没有新模型、服务器或付费资源。单次600秒不延长。

## 真实组件与失败记录分别列出

- F03首轮独立读回 **22/23**：原件、完整解析/checkpoint、实际缓存/Milvus向量与Redis均通过，结束时“served identity及profile仍可认证”布尔检查false，整体保留FAIL。原报告没有保存可区分Optional为空与不同profile的结束对象，不能倒推为已换过模型，也不能倒填具体网络原因。
- F04自然完成后，F03原样只读第二轮 **23/23**、F04首轮 **23/23**。当前serving两次实际GET200、profile SHA均为 `63f361e87f2e69a7cb7eb06edd15ffef2a0e526dc1d77117cc81676f1ef0f364`，与原模型/版本/1024维证书一致。包括PG完整输入与epoch、MinIO原件和structured字节、完整当前checkpoint、实际Milvus1024有限归一向量/内容/证据/成员、认证缓存键和实际向量字节一致、900token边界、原Redis消息及history恰一次、终态不在PEL。
- 实际权限与保留读回 **7项通过**：原任务FAILED6/NULL、旧块6/10保留，新v2/current pipeline、继承原显式密级；普通非所有者对两个旧源重新解析均403，匿名详情401。两新源按原显式level10对两level10账户可读，符合当前元数据。**这不是财务密级差异验收通过。**
- 最初按“财务应为30”的预期报告保留 **FAIL**。实际旧v1/newv2都是level10，admin/reader当前也都是10；F03内容文案称受限财务，与旧种子元数据不一致。未擅自把级别改30，不把reader200记为权限隔离成功。受限财务的预期分类仍需后续确认/修正。
- 中间第二次权限读回SQL的USING(version_id)因前面两个表同名列而失败，未生成PASS；改为明确m.version_id=v.version_id后才得到上述7项，不把失败算通过。

对应私有报告：legacy-original-readback-1、legacy-f03-raw-readback-1/2、legacy-f04-raw-readback-1、legacy-reparse-preservation-access-1/3、legacy-reparse-serving-identity-2。读回不会执行新推理、重试或发布。

## 补通旧失败任务的页面入口

最初真实CUA发现：FAILED版本没有kb_source_authority，资料库搜索返回0，无法从资料详情发起重新解析。正常后台服务有针对FAILED原件的管理员/所有者授权入口；首次两份新v2因此经这个既有正常服务提交，不能记成从旧资料列表点击完成。

本批最小纯前端修复沿既有入库记录的version_id连接服务，不放宽资料库/知识查询authority：

- `DocumentReparseDialog`抽出原资料库确认、busy/错误/重复复用提示，由资料库和入库页共享。
- 管理员在FAILED且有version_id的文件行看到“以当前配置重新解析”，调用同一受权服务。普通成员隐藏此管理操作，服务本身仍校验账号、所有者、当前密级及原件；无version_id不构造假ID。
- 解析身份未知的旧任务不再仅提供无效的原任务重新处理；中央文案明确“身份缺失或不匹配”，不把未知说成确定的模型/规则变更。旧任务仍显示第6次失败。
- 返回复用/新任务后可直接“查看处理记录”，使用原batch权限接口。不会把200/排队提示当生成成功。

修改文件：web/src/enterprise/DocumentReparseDialog.tsx、DocumentsPage.tsx、IngestionHistoryPage.tsx、IngestionFailure.tsx；新增明确合成API夹具ingestion-reparse页面及4项组件测试。既有正常资料页、异步请求、权限错误、范围原文和等待提示合计 **25项Chrome组件最终通过**；lint/types/build正常发布。首轮24/25因新对话框同名heading使旧严格选择器匹配两个节点，修正来源信息为普通文本后回归通过，未放松断言；失败日志保留。

最终前端发布00:46:47 +08:00，`legacy-reparse-web-manifest-2.json`绑定 **52源文件/25资产**。正常入口先复制hashed资源再更新index，旧hashed资源保留；后端ef29/原进程不变。

## 最终computer use与幂等读回

真实Chrome分别筛选F03/F04的原FAILED6批次，点击新恢复按钮、核对原件名称、确认，再看到“已复用原任务，尝试次数不会重置”；随后点击“查看处理记录”打开实际v2第一次成功的任务。此时两v2已经由前面的真实解析产生，本次页面操作正常复用，不制造第3版本或再次占用模型。

PG前后 **4/4**：仍是两旧文件+两新文件、总4版本；原FAILED6与新SUCCEEDED1完全不变。独立截图：browser-f03-old-failed-reparse-reused-final.png、browser-f04-old-failed-reparse-reused-final.png、browser-f04-reparse-success-linked-final.png；全页上下文见browser-f04-old-failed-reparse-reused-full-final.png。真实页面还核对两份v2的实际正文，F04付款仍有合法发票与书面验收两个条件，未将原失败块改成成功。

复跑手工步骤：打开 http://127.0.0.1:18481/enterprise/ingestion ，搜索EKB-F03-财务登记或EKB-F04-采购合同，筛“失败”，打开10月3日的旧批次，使用“以当前配置重新解析”。当前同原件/同pipeline应复用已有v2。仅管理员可见恢复操作；不要清空历史或通过改名绕开幂等。

只读SQL见[legacy-reparse-readback.sql](../../deploy/acceptance/20261102/legacy-reparse-readback.sql)。完整原件、正常提交、过程状态、失败及独立原生读回在Git忽略的.tmp/acceptance-20261102/。没有源码/原件上传、Git推送或数据卷删除。

## 仍未通过/未测

旧NULL管线记录仍无法认证，保留为旧失败；其当前v2真实重解析闭环已通过，不再把这两个旧FAILED称为只能永久停住。F03旧种子的文案/密级不一致未修，不能宣称财务隔离完成。原固定双页PDF14/15及600秒超时、交付条目的待关联STALE、正式质量大集、真实Wiki升阶和先前KB-03/长期测试仍按原记录列未关闭；本批没有扩大验证结论。

## 日期显示窄修（2026-11-03 02:03 +08:00）

真实API先核对F04 v2：document_date为NULL，parsed_at为`2026-11-02T16:35:04.373134Z`，对应上海11月3日00:35。旧资料库把该UTC字符串直接slice(0,10)，因此错误显示11月2日；这不是原件、解析或入库业务日期发生变化。

只改DocumentsPage的parsed_at回退显示，复用既有ui日期工具中的Intl本地日历日格式；显式document_date仍原字符串优先，date-only不经过Date或时区转换。没有改后端ef29、模型、数据库业务日期或任何生成/失败任务，也未重跑后端门禁。

新增两条有意义Chrome组件：Asia/Shanghai下跨午夜UTC timestamp显示11月3日；America/Los_Angeles下显式date-only仍保持11月3日并覆盖解析时间（防止被转成前一天）。修前目标2项为1失败/1通过，完整失败trace、截图及日志保留在document-local-date-red-artifacts-1。修后原25项加2项共 **27/27通过**，lint/types及正常build-web发布通过。原25项报告和旧截图未改写。

最终日期前端发布02:03:37，document-local-date-web-manifest-1.json绑定52源文件/25资产，旧hashed资源保留；后端仍ef29/PID93814，原252文件校验不变。实际Chrome在自己的旧tab刷新，F04 v2渲染2026-11-03；修前browser-f04-document-date-before.png与修后browser-f04-document-date-after.png分别保存。父窗口的修前截图保留，未操作其IAB页面。CUA只读求值器不暴露Intl全局，未声称从该观察器获取浏览器时区；实际DOM与受控时区组件证据分别留存。

认证只读API前后对照 **2/2**：version_id、version_no、document_date与parsed_at逐字不变，后端进程/不可变JAR不变（document-local-date-api-before/after-1）。本批没有新生成、重解析或状态改写。实际命令为：

```sh
EKB_E2E_BROWSER_CHANNEL=chrome EKB_E2E_BASE_URL=http://127.0.0.1:18487 \
  npm --prefix web run e2e -- document-reparse.spec.ts ingestion-reparse.spec.ts entry-scope.spec.ts task-failure.spec.ts
python3 scripts/deploy-target.py build-web \
  --log .tmp/acceptance-20261102/document-local-date-web-release-1.log
```

通过依据见document-local-date-web-e2e-1.log、document-local-date-lint-1.log、document-local-date-types-1.log、document-local-date-web-publish-1.log；旧真实模型/Raw/权限假设失败及其边界不因日期修正而改变。
