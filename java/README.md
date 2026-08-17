# Java backend

Enterprise Knowledge Base 后端采用 Java 17 + Spring Boot 的 DDD 模块化结构。

```text
ekb-domain          领域模型与领域规则
ekb-application     用例、业务编排与端口
ekb-infrastructure  MySQL / MinIO / OpenSearch / Neo4j / MinerU / 模型适配
ekb-interfaces      REST / MCP / 认证等入口
ekb-bootstrap       Spring Boot 组合根
```

构建与测试：

```bash
mvn -f java/pom.xml clean test package
```

最终可执行文件：

```text
java/ekb-bootstrap/target/enterprise-knowledge-base.jar
```

运行时配置统一使用 `EKB_*` 环境变量，完整说明见 `../docs/CONFIGURATION.md`。
