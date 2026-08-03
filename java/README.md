# Enterprise Knowledge Base - Java DDD implementation

This directory is the Java parity implementation of the verified Go enterprise knowledge base.

Build:

```bash
mvn -f java/pom.xml clean verify
```

Modules:

- `ekb-domain`: pure domain model and domain services.
- `ekb-application`: use cases and ports.
- `ekb-infrastructure`: MySQL, MinIO, MinerU, OpenSearch, Neo4j and Spring AI Alibaba adapters.
- `ekb-interfaces`: REST/MCP/security adapters.
- `ekb-bootstrap`: Spring Boot composition root.

The existing `web/` React application remains the UI parity target.
