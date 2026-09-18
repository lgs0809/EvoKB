# 当前知识条目与原文浏览

本机入口 `http://127.0.0.1:18481/enterprise/wiki`。默认“知识条目”展示当前有效的轻量 Concept 和已扩展 Wiki，可按名称、别名、类型搜索并切换阶段。完整百科保留既有阅读、历史与对比入口。轻量条目不冒充完整百科，范围尚未确定时明确限定于来源资料。

正常接口为 `GET /api/enterprise/knowledge-entries?page=1&page_size=30&q=...&stage=ALL` 与 `GET /api/enterprise/knowledge-entry?id=...`。服务端使用运行时知识库、当前登录身份与 clearance；分页和计数前校验全输入 ACL、当前有效来源/摘要及全部事实证据，而不是只过滤返回页面里的一个引用。来源撤权、失效或有待关联变更时旧内容隐藏；未知或不可读明细均为404，无旧prose回退。一次SQL快照读取描述和全部成员来源，列表默认30条/上限100条。

普通成员也可以浏览其有权读取的当前条目，点事实旁“查看原文”再“查看原文件”读取当前原件。表格证据复用 `KnowledgeMarkdown` 的格式标签白名单和 DOMPurify；仅允许 colspan/rowspan，不执行原HTML的事件、脚本或链接。普通文本仍按原文字显示，复制原文不改持久化文本，技术定位信息默认折叠。

条目浏览、原件预览或下载不冒充查询贡献。只有 `query_source_package` 实际完整交付并符合现有门控时，原有独立贡献机制才可累计；本批未修改5次升阶阈值、午夜调度或任何生成权限。

## 可重跑的实际验证

```sh
# 只读正常API+当前证据，不改库、不调用模型，不制造生成成功。
python3 scripts/test-target-knowledge-entry-browse.py \
  --jar-sha256 <.tmp/target/process.json中的jarSha256> \
  --output .tmp/manual-entry-http.json

# 原件名称/真实Reader权限/当前来源及流式字节与PG SHA校验；不会替代浏览器预览。
python3 scripts/test-target-original-preview-contract.py \
  --jar-sha256 <实际已部署Jar SHA-256> \
  --evidence-id <当前可读小原件的Evidence UUID> \
  --output .tmp/manual-original-preview-http.json

# 隔离真实PG权限/全inputs/分页/同名范围/失效/删除。source overlay必须明确标识。
python3 scripts/test-knowledge-entry-browse.py \
  --jar-path <实际immutable artifact> --output .tmp/manual-entry-native.json
```

2026-10-03部署560e067e…后，`entry-browse-actual-http-1.json`、`-2.json`和`-3.json`各12/12通过，普通成员+管理员实际HTTP与只读PG全证据交叉确认。三次分别覆盖最初4个条目、补充资料关联期间3个STALE条目被隐藏，以及实际生成完成后6个ACTIVE条目；其中原合同和双方公司的ID保持不变、修订为2，并公开当前两个来源。native2 17/17为冻结源码overlay、不是同Jar全部重测。实际普通浏览器截图与DOM保存在 `work/resume-20261003/entry-browse-browser-1`，新网页由 `web_release.build` 独立outDir验证后原子发布，保留旧hash资产，避免已开页面懒加载失效。

管理员和只读成员实际页面均可打开合同修订2，看到30日改15日、900万元及24个月不变，并查看补充协议的72小时核验、书面验收及合法发票前提。搜索无匹配结果与当前没有扩展百科分别提供真实空状态；轻量条目不被标记为完整Wiki。`table-drawers-result-2.json`实际8/8通过，含DOCX合并表头、PDF全部行和金额、付款前提、真实PDF页内高亮，以及“复制原文”与PG持久化原文逐字一致。原测试探针首轮错误使用大写TABLE而取不到原生小写table的失败报告保留。

560e067e…的分片上传补充协议原件下载实际3/3通过：普通成员正常浏览器下载文件名正确，962字节与当前PG记录的SHA-256一致。但其在线Markdown预览因对象键末尾固定为original而未识别格式，实际失败截图`reader-annex-current-preview-unsupported-failure.jpg`保留。通用修复通过`source_filename`传递原件名称，预览优先用此名称识别PDF和文本，兼容旧服务缺字段；早期`source-preview-canonical-web-verify-1.json`仅确认独立候选lint/type/build通过、源码稳定，当时尚未部署和重测，不能用该报告证明线上预览。

随后fd7d9802…正常部署，新网页两次独立lint/type/build和原子发布均通过。`source-preview-canonical-browser-1/result-1.json`真实11/11通过：管理员和普通成员均打开相同Markdown完整原件，页面全文逐字等于正常下载的962字节；两角色均显示真实PDF画布、当前页和表格证据高亮。确切页面点击的MD/PDF Evidence另有`source-preview-canonical-actual-http-3.json`10/10正常Reader HTTP＋只读PG全文件SHA核验。不同前端发布版本明确记录，未宣称重新执行PDF模型解析。

同批知识生成页三条真实失败记录均已有5次尝试，显示“已达5次尝试，任务已隔离”，没有重试动作；原有1次失败记录仍保留正常重试入口。仅修正页面与既有服务端5次预算的显示一致性，没有清零任务、绕过隔离或重新执行旧失败。模型配置页只读确认Milvus提示及Luna/Terra既定角色；未修改模型、密钥或超时配置。

旧loadedpage与新loadedpage的表格源码展示缺陷截图保留，不能把仅重载或待修源码算作修复通过。普通Annex实际自动来源关联/Summary/Concept演化另有任务与版本证据，本批浏览测试不替代它的完成验收。
