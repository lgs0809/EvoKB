# 安全说明

## 默认部署边界

Docker Compose 默认将应用和基础设施端口绑定到 `127.0.0.1`。这是开发和单机部署的安全默认值，不代表公网部署方案。

需要远程访问时，应由 HTTPS 反向代理统一暴露应用，并保持数据库、MinIO、OpenSearch、Neo4j 管理端口在受控网络中。

## 身份与会话

- 系统没有内置管理员账号或默认密码。
- 首次启动由用户创建第一个管理员。
- 账号分为 `ADMIN` 与 `USER`。
- 管理员可控制普通用户是否允许上传资料。
- 禁用用户会使其现有会话失效。
- 最后一个可用管理员不能被意外禁用。
- 密码使用 BCrypt 存储，不保存明文密码。

生产 HTTPS 环境应设置：

```text
EKB_SESSION_COOKIE_SECURE=true
```

只有在请求确实经过可信反向代理时才启用：

```text
EKB_TRUST_PROXY_HEADERS=true
```

## Secret

真实 Secret 不得进入仓库。`.gitignore` 排除 `.env` 和本地配置，`.env.example` 只包含占位符。

建议通过部署平台的 Secret 管理能力注入：

- Chat / Embedding / Rerank API Key
- MySQL 密码
- MinIO Secret
- Neo4j 密码

不要把 Secret 写入前端环境变量。浏览器只调用本产品同源 API。

## 上传安全

批量上传包含文件数量、单次上传体积和解压后体积限制。ZIP 处理需要阻止路径穿越并把文件限制在暂存目录内。

可调限制包括：

- `EKB_MAX_UPLOAD_BYTES`
- `EKB_MAX_BATCH_UPLOAD_MIB`
- `EKB_MAX_BATCH_EXPANDED_MIB`
- `EKB_MAX_BATCH_FILES`

实际部署还应结合反向代理设置 request body 限制。

## 外部模型与 MinerU

MinerU、Chat、Embedding 和 Rerank 是外部信任边界。部署者负责：

- 选择可信 endpoint；
- 使用 TLS 或受控内网；
- 明确外部服务的数据保留策略；
- 避免把包含敏感信息的请求发送到不符合组织策略的 Provider；
- 为 endpoint 设置网络访问控制。

项目不会把 API Key 返回给浏览器。

## 数据层

- MySQL 与 MinIO 是主要事实层，应纳入正式备份。
- OpenSearch 与 Neo4j 是派生层，但仍可能包含企业敏感知识，不应无认证暴露到公网。
- MinIO 预签名链接应设置合理有效期，并确保 `EKB_MINIO_PUBLIC_ENDPOINT` 指向受控地址。

## 审计

管理员可查看关键操作审计。公开部署前应结合组织要求设置日志保留、集中收集和访问控制。

## 发布前扫描

仓库 CI 和发布检查应至少扫描：

- 私有绝对路径；
- `sk-` 等常见 API Key 前缀；
- PEM 私钥头；
- 明文 password/token/secret；
- 本机或其他项目路径；
- 生成产物和本地报告。

发现疑似 Secret 时，应先吊销对应凭据，再清理 Git 历史；仅删除最新文件不能撤销已经公开的 Secret。

## 漏洞报告

如果公开仓库启用了 GitHub Security Advisories，安全问题优先通过私有安全报告渠道提交；不要在包含可利用细节或真实凭据的公开 Issue 中报告漏洞。
