# Enterprise Knowledge Base Java DDD Parity Plan

## Goal

Rebuild the enterprise product in Java without deleting the verified Go implementation until parity is proven. The Java implementation is a modular monolith with DDD boundaries and hexagonal ports/adapters.

## Verified Go product baseline

The Java version must preserve these product capabilities:

1. Identity and access
   - first-admin setup, login/logout, session authentication
   - ADMIN/USER RBAC
   - user lifecycle and password reset/change
   - runtime USER upload switch, default off
2. Managed ingestion
   - single upload and multi-file/folder upload
   - server-side safe ZIP extraction
   - persistent upload batches and resumable recent batches
   - per-file metadata editing before ingestion
   - duplicate/version hints
   - async ingestion worker and persisted progress
   - failed-file retry and interrupted-job recovery
3. Raw knowledge pipeline
   - MinIO original/parsed artifact persistence
   - MinerU Pipeline parsing
   - stable Raw Block and Evidence generation
   - Smart Chunking with heading/table semantics
   - embedding
   - OpenSearch BM25 + vector indexing
   - version activation/deactivation and derived-knowledge invalidation
4. Retrieval and Ask
   - Raw/Summary/Wiki/Graph four-lane retrieval
   - per-lane quotas and lane-local fusion
   - one balanced cross-layer rerank
   - Evidence Resolver back to raw source
   - metadata filters
   - Evidence-first Ask
   - citation fail-closed
   - advanced search match explanation
5. Knowledge compilation
   - high-value/distillation recommendation
   - typed document unit classification
   - enterprise Summary generation
   - grounding and coverage repair/gates
   - structured relation extraction
   - canonical entity resolution
   - relation evidence support
   - Neo4j projection
   - Wiki generation with independent writer/judge
   - Wiki claim grounding and coverage gate
   - Wiki keyword/link graph projection
   - Summary/Wiki derived OpenSearch indexing
6. Evidence and source packaging
   - evidence lookup
   - authenticated original source streaming
   - package_sources ZIP generation/publication
   - page/bbox context when MinerU exposes page_size
7. Operations
   - dashboard/needs-attention overview
   - server-side pagination/facets for documents/wiki/evidence/tasks/audit
   - ADMIN global task scope / USER own ingestion task scope
   - audit actor/action/target/outcome with privacy-safe details
   - trusted-proxy opt-in for client IP
   - artifact quality evaluation from Golden JSONL
8. Interfaces
   - enterprise REST API used by the existing React SPA
   - MCP tools: `search_knowledge`, `package_sources`
   - health endpoint
   - existing Wiki and Graph visualizations remain available

## Bounded contexts

| Context | Domain responsibility | Primary truth/adapter |
| --- | --- | --- |
| IdentityAccess | users, roles, sessions, access policy | MySQL |
| Ingestion | batches, staged files, ingestion tasks/progress | MySQL + MinIO + MinerU |
| Knowledge | document/version/raw/evidence lifecycle | MySQL + MinIO |
| Retrieval | four-lane candidate retrieval, rerank, evidence resolution | OpenSearch + Neo4j + MySQL |
| Compilation | Summary/Wiki/Graph knowledge compilation and grounding | Spring AI Alibaba + MySQL + OpenSearch + Neo4j |
| Evidence | evidence/source resolution and source packages | MySQL + MinIO |
| Operations | audit, admin read models, evaluation | MySQL |

## Module dependency rule

```text
ekb-domain
    ^
    |
ekb-application
    ^         ^
    |         |
ekb-infrastructure   ekb-interfaces
           ^         ^
            \       /
             ekb-bootstrap
```

- `ekb-domain` is pure Java and has no Spring/database/client dependency.
- `ekb-application` owns use cases and ports, not technology.
- `ekb-infrastructure` implements persistence, object/search/graph/parser/model ports.
- `ekb-interfaces` owns REST/MCP DTOs, authentication filters and error mapping.
- `ekb-bootstrap` is the composition root only.

## LLM integration rule

All chat/structured-output calls go through an application port (`KnowledgeModel`). The production adapter is implemented with Spring AI Alibaba/Spring AI `ChatClient`. No domain object imports `ChatClient`, OpenAI SDK types, HTTP model DTOs, or prompt framework types.

Configured model roles:

- small model: classification, grounding judge, coverage judge, lightweight extraction
- large model: Summary generation, Wiki generation, relation extraction/repair

The model endpoint remains OpenAI-compatible and is configured only through environment/application properties.

## Persistence rule

`MinIO + MySQL` remain the durable fact layer. `OpenSearch + Neo4j` remain rebuildable derived indexes. The Java version reuses the existing logical schema and object-key conventions so Go and Java parity tests can run against isolated schemas/buckets/indexes without changing product semantics.

## Migration/commit gates

1. `java-ddd-foundation`: module graph, architecture contract, domain primitives.
2. `java-core-domain`: identity, ingestion, document/version/evidence, retrieval/compilation domain and use-case ports.
3. `java-infrastructure`: MySQL/MinIO/MinerU/OpenSearch/Neo4j/Spring AI Alibaba adapters.
4. `java-interfaces`: REST + MCP + security/API compatibility.
5. `java-parity`: Flyway schema, existing SPA integration, contract/integration tests, Docker deployment.
6. `java-cutover-ready`: parity matrix green; Go implementation is retained until this gate passes.

No paid LLM acceptance is required for every commit. Deterministic fake model adapters cover most tests; real-model smoke is only used after the full path is wired.
