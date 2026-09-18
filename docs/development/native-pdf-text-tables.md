# PDF 文字层与几何表格选择

MinerU 3.4.5 的 `hybrid-engine / medium / auto` 会识别文字与扫描情况，但表格主体仍经过视觉模型抽取。一次实际两页验收在 51.786 秒内完成解析，独立业务检查为 12 通过、3 失败：数字文字页的两行内容丢失，二级表头被改写。扫描页通过。原 PDF 像素、文字层、`source_model.json`、middle、内容列表和失败报告均保留；任务完成不能代替内容正确。

`scripts/mineru-service/pdf_text_tables.py` 使用固定的 pdfplumber 0.11.9 / pdfminer.six 20251230 提取数字 PDF 的文字与线框表格。它没有模型调用，不使用文档名称、领域关键词或期望答案。原 MinerU 调用、页数、模型 manifest、`auto` 参数和 600 秒阶段预算不变。

只有这些条件同时成立才选取文字层表格：

- pdfplumber 的格子覆盖整个表格，真实几何能确定 row/column span，不存在重叠或缺口。
- 每格的非空白字形完整保留；有跨格或旋转字形时拒绝认证。标点、正负号、数字、单位不允许替换。
- 表格区域没有栅格图片覆盖，页面不是旋转页。扫描页、图表和没有可靠文字表格的区域继续使用原 MinerU。
- 同页原生表格与 MinerU 表格的 IoU 至少 0.90，映射唯一；源页索引和大小一致。存在多个匹配、重复使用、对应的不完整来源或结构歧义时，以 `QUALITY_REVIEW_REQUIRED` 拒绝输出。

文字中的格式空白可以转换为空格，正文和 HTML 转义仍忠实来自原文字层。字形与几何校验是保真检查，不是所有 PDF 的视觉正确性证明，也不保证模型路径的其他扫描内容正确。

所选 HTML 会同时进入 middle 的结构视图，再由 MinerU 自带的 `union_make` 生成 Markdown 和内容列表。每个所选表格带有 `text_table_provenance`：原件 SHA、页面和原文格子坐标、实际 span、源文字与 HTML 哈希、原视觉 HTML 哈希、选择策略。原始 `native-output` 的模型响应、middle、Markdown、图片都不会被覆盖。`native-text-tables.json` 单独记录本次选择。现有 Java parser 保留完整 raw block 和 parser receipt，仍通过原资料权限保存和读取；没有新增绕过权限的入口。

服务启动冻结 app 和选择模块，`runtime-bundle.json` 把两份源码哈希绑定为一个 runtime 身份；ready 与 parser profile 均返回 bundle/module 哈希。模块改变必须新建冻结目录。已保存目录中任何字节不一致会拒绝覆盖；加载模块也必须来自该冻结目录。明确回退到保存的旧单文件 runtime 时，保留其原 source 身份，不把旧 runtime 当作使用新策略。

组件验收（不下载或运行模型）：

```bash
python3 scripts/test-mineru-text-tables.py --output /absolute/path/fresh-component.json
python3 scripts/test-mineru-observation.py --output /absolute/path/fresh-observation.json
```

组件测试依赖固定 pdfplumber、pdfminer.six、ReportLab 和 Pillow。它实际生成数字表格、合并格、空格、栅格覆盖及图表 PDF，验证保真、拒绝条件和冻结 runtime。可另传 `--native-site`、`--fixture` 和 `--upstream-middle`，使用现有安装的 MinerU 对已保留的上游输出执行原生格式转换；这仍是组件测试，不是一次新的解析通过。`--legacy-runtime-source` 可以核对真实保存的旧单文件版本，否则使用独立单文件测试输入。

真实解析必须另行执行原 `test-mineru-scanned-table.py` 的全部 15 项断言，保存新 task、原件哈希、完整 native 日志、30 秒栈、SQLite 状态与 actor 释放证据。既有 3 次 600 秒失败和第 4 次质量失败不改为成功，不修改 Gold，不借用组件输出代替真实推理。截至本批组件完成时，新的实际解析尚未执行；已有 Java 部署和回归不能计入该 parser 批次的通过数。

## 后续真实解析验收：2026-10-07

组件冻结后，按正常脚本部署完整 app/module bundle，再从 parser HTTP 创建新的原两页任务 `202c6b0c-1b26-4967-9c39-cf30865ad528`。原件、原 15 项检查、Gold、模型 manifest、MPS/4CPU/600 秒均不变。本次 15 项全部通过：数字页与扫描页原行、金额、完整条件、重复单位和表头、真实合并格及页面位置都由原断言校验。实际服务任务用时 153.181 秒；探针包含轮询为 155.698 秒，完整外部观测为 156.957 秒。

另外 11 项真实读回确认本次 source/bundle/dependency 绑定、原视觉输出物理保留、扫描页与该任务原 middle 一致，以及所选 middle、官方 `union_make`、HTTP 内容列表/Markdown 和磁盘产物一致。只选择了数字页一个表格，扫描页保留本次 MinerU 结果。原 23 条 parser 任务不变，正常新增 1 条 COMPLETED；前三次超时和第四次质量失败仍保留。通过范围为这一个固定两页测试，没有把历史失败改为成功，也没有宣称所有 PDF 都已通过。

解析结束后实际停止闲置的 owned actor 与渲染进程组，确认 live0，再恢复同一 bundle 的惰性 listener；24 条账本不变，worker 为 null。6 项资源释放/恢复读回通过。这是已完成解析的缓存模型资源释放，不是活动任务故障恢复。新代码下的 Office 重启、取消和故障恢复还需单独实测。

该 parser 结果与知识生成分开验收：本批没有新增浏览器 Raw 入库到 PG/MinIO/Milvus 的完整发布链，也没有用旧 Summary/Concept/Graph/Wiki 成功记录代替新业务任务。Java 运行产物及其既有回归数不变。实际证据位于本地验收目录的 `mineru-observed-attempt-5/`；敏感配置和完整原始响应不加入版本库。
