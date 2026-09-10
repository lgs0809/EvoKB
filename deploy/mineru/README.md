# 隔离的 MinerU 3.4.5 原件解析

本机解析器协议为 `evokb-mineru-isolated-v1`，并非把上游 `fast_api` 接口或取消能力冒充成已有能力。原生解析函数来自实际安装的 MinerU 3.4.5。模型固定为文档中的 `hybrid-engine / medium / auto / ch`，不在缺依赖时切换其他解析器。

## 安装和启动

Python 3.12；本机可使用 Codex 已有 Python，或自己建立隔离虚拟环境。不要对共享 `embedding-model` 安装这些依赖：它的 Transformers 5 与 MinerU 所需 `<5` 版本不同。

```sh
python3 -m pip install --target .tmp/mineru-hybrid/site --no-cache-dir \
  --report .tmp/mineru-hybrid/pip-report.json \
  -r deploy/mineru/requirements-hybrid.txt
python3 scripts/download-mineru-hybrid-assets.py --help
```

模型下载脚本按固定仓库 revision 只下载 25 个必要文件，共 3,387,605,040 bytes（3.155 GiB）。它核验大小、LFS SHA256 或 Git blob，支持最多五次失败重试和断点续传，不下载 15 GB 的 ExtractKit 全仓，不加载模型。具体参数看 `--help`，下载完成后保留 `models/manifest.json` 与实际下载回执。

本机实际依赖锁不是 requirements 的猜测：保留 pip report（每个 wheel 的实际 URL / SHA256）以及 `raw-mineru-hybrid-installed-2.json` 中的 91 个实际发行版本。实际展开安装体积 1,065,064,910 bytes，未复用共享服务权重。模型文件分别固定为：

- `opendatalab/MinerU2.5-Pro-2605-1.2B@bff20d4ae2bf202df9f45284b4d43681555a97ed`；VLM 权重 SHA256 `abf8681ca63b8dec7b67de257af47b821f179442f72998d0696ae2ed9232a5f0`。
- `opendatalab/PDF-Extract-Kit-1.0@ed6b654c018d742e65a17671e379c5e6ecc87ec9`；仅 LayoutPPDocLayoutV2、Unimernet Small 2503、中文 OCR v6 small 及对应配置。

Office 服务可独立启动；PDF 必须显式本地模型 manifest：

```sh
python3 scripts/start-mineru-service.py --site .tmp/mineru-office/site \
  --workspace .tmp/mineru-service --port 18486 --stage-seconds 600

# 在资源允许时单独启动完整 PDF 服务；启动本身不加载权重。
python3 scripts/start-mineru-service.py --site .tmp/mineru-hybrid/site \
  --workspace .tmp/mineru-hybrid/service --port 18489 --stage-seconds 600 \
  --hybrid-manifest .tmp/mineru-hybrid/models/manifest.json --device cpu
```

所有端口仅绑定 `127.0.0.1`。启动回执保存 PID、原参数、日志和精确停止命令。已有服务不会被脚本擅自替换；更换 site/manifest 时需先核对旧 PID 与命令，然后仅停止该隔离服务。文档中的原参数仍然是 `auto` 解析方法；`--device` 只是执行设备，模型、backend、effort 不变。

完整服务显式设置 `MINERU_MODEL_SOURCE=local`、绝对路径 `MINERU_TOOLS_CONFIG_JSON` 与 offline 环境。每个 PDF 请求核验 manifest 的全部大小/SHA256；原生导入前检查一致性，禁止隐式远程下载或未知模型回退。默认CPU两线程；`--cpu-threads 1..8`固定实际Torch/OMP/MKL线程数并记录证据，不改变模型或阶段期限。模型实际载入可能占用数 GiB 内存，勿与全量 Maven/共享 Qwen 压测同时启动。

本机的隔离 hybrid wheel 为 Python3.12，系统的 `python3` 可能仍为3.9，必须用实际部署回执 `args[0]` 或已有3.12解释器，不能把cp312依赖放进系统运行时。`replace-mineru-service.py`在SQLite无queued/running任务时才停止保存的准确PID，保留旧 runtime、launch、SQLite、原件、模型与精确回滚参数；不会因参数变化自动重启共享模型。

```sh
# 用本机已配置的Python3.12；不需要重新安装/下载依赖。
<已配置的Python3.12> scripts/replace-mineru-service.py \
  --device mps --cpu-threads 4 --output .tmp/manual-mineru-mps-deploy
```

MinerU3.4.5实际安装源码原生通过`get_device()`选择MPS，并把transformers Qwen2VL模型映射到该设备。hybrid原框架允许`PYTORCH_ENABLE_MPS_FALLBACK=1`，所以MPS执行仍可能有CPU内核，不能宣称所有步骤GPU化。已有PyTorch2.8的真实MPS tensor运算和设备选择已核验；完整解析是否通过以独立Gold报告为准。

## 执行、取消、恢复

任务原件、native middle JSON、content-list-v1、Markdown、图片和失败日志存于 workspace；SQLite 是该解析执行端的账本。单个常驻、独立子进程串行解析，避免每个原件重载模型。原件同名仍按 UUID 隔离。

- `POST /tasks`：一次一个原件，multipart `files` 与固定 parser 参数。
- `GET /tasks/{id}` / `/result`：任务状态、完成结果；结果未完成为 409。
- `DELETE /tasks/{id}`：取消该任务，终止本服务拥有的子进程；只有确认释放才返回 `resource_release_confirmed=true`。
- `GET /tasks/{id}/images/{path}`：按任务提供实际图片字节。Java 适配器核验图片大小/SHA256 后存入 MinIO，不丢弃图片。

阶段上限 600 秒；无续约。旧子进程真正退出后下一任务会重建。服务重启时旧 RUNNING 记录显式失败 `PARSER_PROCESS_INTERRUPTED`，排队原件保留 UUID 继续执行；不把执行端 SQLite 状态当业务发布成功。业务层 owner/epoch 与 Redis PEL 属于另外的入库任务治理，需独立验收。

Office 的原 `page_idx` 只代表元素顺序；适配后不制造 PDF 页码/坐标。PDF 要覆盖全部实际页且每页有有效区域；损坏/加密文件报 INPUT_ERROR，缺模型为 DEPENDENCY_NOT_AVAILABLE，关键空白为 QUALITY_REVIEW_REQUIRED。

## 实际验收与尚未验收

2026-10-03 本批真实结果：

| 范围 | 结果 |
|---|---|
| 原生 DOCX 解析：合并表格、单位、付款条件、图片 | 7 / 7 PASS，实际 MinerU 3.4.5 |
| HTTP、3 个并发同名原件、损坏输入、真实取消、重建子进程、SQLite | 8 / 8 PASS |
| 服务重启、相同排队 UUID 恢复、实际 1 秒缩短期限终止 | 6 / 6 PASS；期限实验是加速测试，不算等待了生产 600 秒 |
| 原件 MinIO → 原生解析 HTTP → 图片 MinIO → 实际 tokenizer/切块 | 6 / 6 PASS，冻结源码和实际对象哈希已保存 |
| 固定 PDF 模型/依赖下载与校验 | 完成；早期依赖解冲突失败、MFR 网络重试均保留 |
| 单页原生 PDF hybrid 推理、表格关键值/条件/位置核对 | 第二次实际 8 / 8 PASS；278.385 秒，进程组峰值约 1,270 MiB，固定 CPU 两线程；已确认所属进程组完全退出 |
| 两页完整原件：电子页＋真实扫描页、重复多层表头/跨页表 | CPU2、CPU4和原生MPS均在实际600秒内未完成；Gold和三份原失败保留。MPS支持成立不等于完整解析成功；尚无扫描件/复杂跨页表格质量通过 |
| XLSX / PPTX 实际解析 | 后续实际 10 / 10 PASS；XLSX 行/金额/条件/原结构/合并格，PPTX 行/金额/条件/原结构/图片字节；不当作 PDF 跨页能力证明 |

失败记录没有覆盖：首次服务源码缩进失败；独立 MinIO 探针三次进程退出超时保留，线程 dump 确认是完成读回后 SDK 空闲线程使独立 JVM 未退出。PDF 首次实际失败于本服务 daemon actor 无法创建原生渲染子进程，修复为独立进程组中的普通进程，终止时关闭所属进程组；另一个过短原件没有触发 1 秒 deadline 的恢复实验保留为失败，新的真实大原件重新验证取消/恢复 6 / 6 PASS。未改官方模型或将超时冒充通过。

完整 hybrid 服务后来正常部署到应用使用的 `127.0.0.1:18486`。浏览器上传单页 PDF，经 Java owner/epoch → 原生 hybrid → 实际 Qwen 编码 → PostgreSQL/Milvus 正常发布，随后在页面打开实际原文件并定位付款条件。该业务执行发生在 `a2d6b8…` 构建，不借后续 reader JAR 重新宣称它是在新构建执行；原件和图片 MinIO SHA、任务账本、缓存及当前物理索引读回独立保存。DOCX 正常浏览器五分片上传和下载原件的后续结果见 [原件任务与分片上传](../target/raw-task-and-multipart.md)。

两页扫描专项原件固定155,311字节、SHA256`aba0b5bbf65e49098cddc6ab2ea166e7ff2c975abed68d714c1efacde1bac471`。第一页776个可抽取字符、第二页0个字符/一张图；四条原行、金额/单位/付款条件、`rowspan/colspan`和两页表格框在Gold中冻结。位置IoU阈值保持0.90，不因模型输出差异调低；不拆成两个单页冒充完整任务通过。

```sh
<已配置的Python3.12> scripts/make-scanned-table-pdf-fixture.py --help
<已配置的Python3.12> scripts/test-mineru-scanned-table.py \
  --workspace .tmp/mineru-service --gold <冻结gold.json> \
  --output .tmp/manual-scanned-table-acceptance
```

`raw-scanned-cross-page-native-1`为CPU2实际`PARSER_STAGE_TIMEOUT`；`native-2`的真实ledger为600秒处`PARSER_WORKER_LOST/EPERM`，清理异常覆盖了到期原因，保留原记录不改库。修复仅在信号前/权限异常后查准确owned进程组存活状态，不能仅凭EPERM推定资源释放。新隔离Office恢复`raw-mineru-cleanup-recovery-2`实际6/6：进程重启、相同queued UUID继续、实际1秒加速期限后返回`PARSER_STAGE_TIMEOUT`且原actor退出；这不是生产600秒推理通过。

`raw-scanned-cross-page-native-3`为实际MPS/fallback1、CPU4、相同全部25份模型资产：两页Layout完成约63.9秒，VLM提取未在600秒完成；终态`PARSER_STAGE_TIMEOUT`，任务9d1161e2-3186-46a3-9969-c7703f28cca8，所有owned进程退出。进程组峰值RSS434.11MiB只代表CPU进程驻留内存，不能冒充峰值GPU显存。实际进程采样含MPSGraph编译/特化调用，不能由此把并发资源认定为唯一原因。

固定3.4.5源码`get_vram`未按MPS统一内存自动取设备容量，默认值1使VLM batch=1、hybrid batch ratio=1；此数字不是本机实际显存容量。`batch_size`可从`do_parse`kwargs传到原生MinerUClient，`MINERU_HYBRID_BATCH_RATIO`是另一个布局/OCR/公式参数，不替代VLM batch。`raw-mineru-performance-inspection-1.json`保存实际日志/源码SHA与这些边界，未把源码检查当性能优化通过，也未放宽Gold/600或替换模型。
