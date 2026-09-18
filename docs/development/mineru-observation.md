# MinerU 原生过程观测

两页扫描与跨页表的原三次固定 600 秒验收仍保留为失败。完整原生日志能确认 VLM 初始化、两页渲染、Layout 和表方向识别已完成；不能凭外层轮询或截断日志断言 Layout 卡住，也不能从 CPU/RSS 推断模型的 MPS 运算或最终根因。

`scripts/mineru-service/app.py` 在本服务拥有的串行原生 actor 中使用 Python 标准库 `faulthandler`。PDF 的原 `do_parse` 调用不变；每 30 秒把所有 Python 线程的栈位置写入该任务的 `native-stacks.log`，原生异常时再保存一次即时栈。上下文结束时取消采样定时器并关闭文件，下一任务有独立目录。被取消或超时的 actor 仍由原有进程组释放流程终止，不让诊断文件或定时器延长 600 秒期限。

采样不包装或替换 MinerU、Transformers、tokenizer 或模型函数；不捕获 frame locals、文档正文、模型输入或凭据。栈中只有线程、文件、行号、函数位置；它只是采样时刻的位置证据，并不表示该阶段已通过或证明阻塞根因。现有原生进度日志和任务账本仍是判定完成/失败的依据。

定位时请同时保留：

- 本次 `launch.json`、`runtime-stages.json`、`native-stacks.log`、完整 native launch 日志、HTTP 请求及 SQLite 任务记录。
- 原始文件 SHA、manifest SHA、parser 源 SHA、固定 MinerU 3.4.5 / hybrid-engine / medium / auto / ch / 600s / 当前设备身份。
- 采样时对应的 pinned 原生 Python 源哈希。不同版本的函数/行号不可直接按旧流程解释。

当前 pinned 原生流程可用栈位置区分这些范围，不能把该列表当作诊断结论：

| 原生范围 | 当前 pinned 位置 |
| --- | --- |
| PDF 分窗和原生 Layout | `mineru.backend.hybrid.hybrid_analyze.doc_analyze`、`_predict_layout_for_window` |
| 方向识别 | 同模块 `_apply_medium_table_orientation_labels` |
| 裁图/抽取输入准备 | `mineru_vl_utils.mineru_client.MinerUClientHelper.batch_prepare_for_extract` |
| VLM 抽取 | `MinerUClient.batch_extract_with_layout`、`_batch_predict`；`vlm_client.transformers_client.TransformersVlmClient._predict_one_batch` |
| 后处理/跨页表 | `MinerUClientHelper.batch_post_process`；`post_process.cross_page_table.detect_cross_page_cell_merge` |

轻量组件命令：

```bash
python3 scripts/test-mineru-observation.py --output /absolute/path/fresh-report.json
```

它验证真实 stdlib 异常栈、不泄露本地值、异常身份传递、定时器取消/文件关闭、连续任务隔离及原 `do_parse` 调用/生产预算未变。它不运行 MinerU 推理，不代表两页扫描或跨页表通过。真实原固定预算诊断要协调本机模型窗口并使用新的任务，不修改原失败任务、原件或 Gold。
