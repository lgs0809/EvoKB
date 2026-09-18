# 回答资料包的实际下载贡献

本批实施 KB-03 的来源有效使用信号，按照《项目全解-EvoKB.md》第 6 节处理。当前主环境仍为 PostgreSQL + Milvus；未修改秋招资料或模型选型。

## 已实现行为

- 普通查询页自动把已保存的回答 ID 传给打包接口，用户仍只需提问、点击下载。查询记录和查询组同样绑定各自的真实回答；组内每个回答只归属自己的引用来源。
- ZIP 内 `manifest.json` 和 PostgreSQL 都保存原件文档/版本清单及回答归属。归属必须是当前用户拥有、已完成且仍获准查看的回答，指定来源必须属于该回答的实际引用。不能借别人的回答或未引用的搜索结果制造贡献。
- 完整复制、关闭来源流、刷新 HTTP 响应后才保存下载回执；传输字节必须等于不可变清单中的包长度。HEAD、Range（返回 416）、生成资料包、搜索、单独原件下载均不增加回答来源贡献。短流、输出失败、关闭失败和响应刷新失败均不调用回执保存。
- PostgreSQL 事务保存回执及来源贡献，当前包所有者、期限、状态、账号权限、全部模型输入来源及包内来源再次校验。重复请求按包/用户去重回执，按用户/回答/文档去重贡献；相同回答重新打包、多个引用片段、查询组重复下载均不多算。
- 历史搜索记录和旧命中行保留。新搜索只记录交付，`effective=false`，不再写 `effective_version_hit` 或基于搜索推荐加工。历史资料包缺少不可变清单时不补造下载事实。
- 资料列表及总览的兼容字段 `effective_hits` 现在读取当前获准来源版本最近 30 天的实际下载贡献，页面名称改为“有效下载”/“下载”。旧版本事实保留，但不会复制成新版本热度。同一回答同时包含某资料的两个版本也只贡献一次；首次记录选择包中实际包含的最高版本，后续下载不改写该历史归属，所有版本仍保留在包清单中。

本批记录来源贡献。后续 V9 已补条目实际展开路径、资产来源目录及独立参与贡献，实测边界见 [条目参与及下载归属](asset-attribution.md)。每天配额、选择性加工及稳定身份归并仍未完成；没有把热门文档热度广播给条目，也没有声称来源达到阈值后已经自动完成 Wiki。

第一版成功口径为服务端完整传输并正常刷新响应，不证明用户读完。若接收端断开在服务端已完成刷新之后才被系统发现，该事实无法由本口径进一步判断。下载成功但数据库回执事务失败时不补算；断点续传和回执补偿队列尚未实现。

## 部署及实际验证

Flyway V7 新增五张独立表，V8 新增稳定资料去重索引，不修改已应用迁移或业务状态。升级前后 15 张既有表的行数及逐行摘要全部一致；V7 成功应用，历史回执/贡献初始为零，共 17 项升级检查通过。V8 升级的 20 张表摘要全部保持，迁移与索引正常，22 项检查通过。

本批最终 JAR 为 `bd0747f717aa85d76a3457c81b492723f3e88b73ba80e51b2d888d72e071860e`。访问 http://127.0.0.1:18481/enterprise/ask。完整 Maven 验证 178 项：177 通过、1 跳过、0 失败/错误；跳过不计通过。

真实 PostgreSQL 25 项通过，独立保留 schema `evokb_usage_probe_2bb8371a9379`。32 个并发完成请求只产生一份回执和两份来源贡献；第二个 JVM 重放的 1 项验证也通过。覆盖当前授权、未引用输入、重新打包、组内归属、旧版本、过期、撤权、删除、事务回滚、短传输等。组件的回答状态是明确标识的隔离夹具，不作为真实模型成功证据。

真实浏览器下载已捕获为 `knowledge-sources (3).zip`，八个引用片段对应两份来源原件。ZIP 内实际原件与种子文件逐字节一致，数据库完整回执字节数匹配，两个来源各增加一份贡献，资料管理页显示各为 1；8 项浏览器/文件/数据库核对通过。

最终 V8 JAR 的正常 HTTP 26 项通过，包括 HEAD 无正文且不计数、Range 416 且不计数、12 个并发完整 ZIP 下载字节一致、重复回执及再打包去重、错误归属拒绝、查询记录与查询组下载。仍使用此前真实浏览器模型回答，未把下载验收冒称为本批重新调用模型。另有公开问答及实际 ZIP 的 25 项回归、真实 MCP 的 30 项权限回归通过（这些回归的执行版本为之前 V7 的 `dacfdcf4…`，保留原报告绑定）。

```sh
mvn -f java/pom.xml spotless:apply verify > .tmp/target/download-verify-manual-01.log 2>&1
python3 scripts/record-backend-verification.py --log .tmp/target/download-verify-manual-01.log --output .tmp/target/download-verify-manual-01.json
python3 scripts/test-postgres-download-usage.py --output .tmp/target/download-pg-manual-01.json
python3 scripts/deploy-target.py build-web --log .tmp/target/download-web-manual-01.log
python3 scripts/deploy-target.py stop
python3 scripts/deploy-target.py start --verification .tmp/target/download-verify-manual-01.json
python3 scripts/test-target-download-usage.py --session c8561809-b99e-45d9-bfa0-044e1a10d4af --second-session 573e6934-ae50-485a-be97-01fe48fc4712 --output .tmp/target/download-http-manual-01.json
docker exec -i evokb-target-postgres-1 psql -X -v ON_ERROR_STOP=1 -U evokb_target -d evokb_target < deploy/acceptance/download-usage-inspection.sql
```

组件脚本每次创建新 schema，SQL、编译、运行及第二进程报告均保留。重复运行正常 HTTP 测试会生成新的验收查询组/资料包，不覆盖真实资料，已发生的贡献保持去重；请换新的输出路径。

所有页面共用下载助手，先用不计数的 HEAD 检查当前可用性，再启动原生下载。前端 lint/typecheck/Vite 已完成并原子发布；没有把 ZIP 内容整体读取进 JavaScript 内存。查询状态、摘要及核验名称改为中文。最终 V8 版本再次通过 CUA 下载查询组 ZIP，8 项文件/数据库核对通过；旧页面的资料包被正常分组操作失效后，点击下载仍留在记录页面并提示刷新/重新打包，5 项 UI/数据库核对通过。

## 失败记录及限制

- 已修复：原文读取对允许为空的 `heading_path` 调用 JSON 解析，导致首个组件检查报错；统一支持 SQL NULL、空 JSON 值后全套回归与真实 PG 检查通过，原失败日志保留。
- 已修复：前端发布助手接收相对输出路径时把 Vite 文件写在 `web` 子目录，发布阶段未找到入口；统一规范绝对路径，重新用相对调用参数实测发布通过，原构建产物/日志保留。
- 已修复：第二 JVM 检查脚本漏传本地 SOCKS 绕过选项，主 22 项已通过但第二进程连不上 PG；两个进程统一选项后全套及第二进程通过，原失败单独记录。
- 已修复/保留：查询组原先用新窗口下载，CUA 未捕获文件事件，不能算通过；统一下载助手后实际捕获查询组 ZIP，4 个原件逐字节匹配，9 项文件/数据库检查通过，重复下载没有增加 4 份来源贡献（V7 JAR 实测）。
- 未测：真实大文件下载中途断开连接；输出/刷新/关闭失败目前由传输组件测试验证，不能混称为真实网络中断。
- 后续已补：条目参与来源的实际展开路径与独立贡献、PG 资产来源目录，见 V9 批次。仍未完成：稳定身份归并及完整晋升、夜间加工队列及配额、完整发布栅栏、Stream 恢复、MinerU/tokenizer/multipart/正式评测。KB-03 整体仍未完成。

实际命令、构建绑定、只读 SQL、ZIP、截图和每个改动文件的差异均保存在 Codex 工作目录的 `work/evokb-implementation-20261002/download-*`。
