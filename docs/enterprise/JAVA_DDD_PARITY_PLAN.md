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
5. `java-parity`: reuse and validate the existing enterprise MySQL bootstrap schema, integrate the existing SPA, pass contract/integration tests, and provide Docker deployment.
6. `java-cutover-ready`: parity matrix green; Go implementation is retained until this gate passes.

No paid LLM acceptance is required for every commit. Deterministic fake model adapters cover most tests; real-model smoke is only used after the full path is wired.

## Java parity acceptance status

The Java implementation has now passed the product-level parity checks below. The Go service remains available as the comparison path and has not been deleted.

| Capability | Java status | Acceptance evidence |
| --- | --- | --- |
| DDD module boundaries | PASS | `ekb-domain -> ekb-application -> adapters -> ekb-bootstrap`; ArchUnit gate passes. |
| Identity/RBAC/upload policy | PASS | setup/login/session/user lifecycle and ADMIN/USER upload policy are covered by deterministic tests and REST smoke. |
| Single/batch ingestion | PASS | direct upload, persistent batches, safe ZIP expansion, per-file metadata, retry/recovery and progress persistence are implemented. |
| Parser routing | PASS | PDF/Office routes to MinerU Pipeline; text/Markdown/CSV/JSON/HTML use the local Java parser without inventing page numbers. |
| Durable fact layer | PASS | original/parsed/compiled assets in MinIO and document/version/evidence/metadata facts in MySQL. |
| Raw retrieval | PASS | OpenSearch BM25 + Vector, lane-local RRF, one cross-layer rerank and final Evidence Resolver. |
| Retrieval resilience | PASS | query embedding is shared across lanes, Evidence resolution is batched, rerank has a finite timeout and falls back to the already-fused RRF pool before Evidence resolution. |
| Evidence-first Ask | PASS | factual answers require raw Evidence citations; invalid citations fail closed. |
| Summary compilation | PASS | real `gpt-5.6-luna` writer + independent `gpt-5.4-mini` judge; grounding/coverage gates and retry were exercised against a real document version. |
| Summary history/provenance | PASS | retry-safe Summary-scoped Fact IDs and immutable Summary object keys prevent history from being overwritten. |
| Graph compilation | PASS | real LLM relation extraction, schema validation, MySQL relation/support persistence and Neo4j projection completed end-to-end. |
| Entity resolution provenance | PASS | SourceEntity -> EntityMapping -> CanonicalEntity is persisted with raw Evidence, confidence and `decided_by`; conflicting mappings fail closed. |
| Wiki compilation | PASS | real writer/judge flow produced ACTIVE grounded Wiki claims and indexed the Wiki derived artifact. |
| Wiki keyword graph projection | PASS | atomic two-entity Wiki claims are projected deterministically; unsupported/ambiguous claims are skipped instead of guessed. |
| Version/business-time semantics | PASS | derived support is invalidated by version lifecycle without rewriting relation `valid_from`/`valid_to`. |
| Evidence/source package | PASS | authenticated source view and version-deduplicated package publication use MinIO presigned access. |
| Operations/audit | PASS | document/wiki/evidence/task/facet/audit read models, actor/action/outcome auditing and trusted-proxy opt-in are wired. |
| Golden artifact evaluation | PASS | Summary Fact, Graph Relation, Entity Resolution, Wiki Claim and Wiki Grounding metrics are computed from persisted artifacts; task success/retrieval rounds remain explicitly unavailable because they are not artifact metrics. |
| REST/MCP | PASS | existing SPA REST contract plus MCP `search_knowledge` and `package_sources` are wired to the same application services. |
| SPA | PASS | production TypeScript/Vite build passes and existing Wiki/Graph visualizations remain available. |
| Java container deployment | PASS | Java Dockerfile and Compose override merge successfully; the final image was built from the reviewed source tree and passed non-root `/health` + SPA startup smoke. |

## Acceptance runs

Latest deterministic verification:

- Java Maven reactor: 46 tests executed, all passed; one opt-in real-model test skipped by design.
- ArchUnit DDD boundary tests: PASS.
- React production build: PASS.
- Docker Compose base + Java override syntax/merge: PASS using the local Compose implementation.

Real infrastructure/model smoke exercised:

```text
upload -> raw blocks/evidence -> Summary -> Graph -> Wiki -> Wiki keyword projection -> Golden evaluation
```

The smoke used the real MySQL, MinIO, OpenSearch, Neo4j and configured OpenAI-compatible model endpoint. The temporary document/version/evidence/Summary/Wiki/entity/user/audit rows, OpenSearch documents, Neo4j projection and MinIO objects were removed after verification; the existing Go data path was not modified.

Observed model-service latency is intentionally not hidden by changing knowledge-quality gates. Rerank and compilation calls can be slow on the current CPU/external model setup; Java uses bounded rerank timeout/fallback while Summary/Graph/Wiki continue to fail closed on grounding or coverage errors.

## Cutover gate

The Java image/deployment gate and repository review/commit gate are green. Before replacing the Go service as the default runtime, the remaining release-only steps are:

1. Re-run the merged Compose configuration in the actual deployment environment with its required infrastructure secrets supplied.
2. Make an explicit cutover decision. The Go baseline remains available until then; removing it is a separate decision, not part of parity implementation.
