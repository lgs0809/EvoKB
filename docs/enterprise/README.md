# Heng'an Jiaxin Enterprise Knowledge Base

This repository pins the upstream Sage-Wiki baseline and adds an enterprise runtime under `internal/enterprise`, `cmd/enterprise-kb`, and `deploy/`. The upstream implementation remains available as a comparison baseline; the enterprise production path uses MySQL, MinIO, OpenSearch, Neo4j, MinerU Pipeline, local Embedding/Reranker, and an optional external OpenAI-compatible compiler model.

## Evidence-first contract

Every Summary fact, Wiki claim, Graph relation, and final search result must resolve to ACTIVE Raw Evidence and an exact Document Version/location. High-level candidates that cannot resolve to Raw Evidence are removed before delivery.

```text
Upload -> MinIO original -> MySQL Document/Version -> MinerU Pipeline
       -> Raw Block/Smart Chunk -> Embedding -> OpenSearch Raw
       -> optional Summary -> optional Graph/Wiki
       -> Raw/Summary/Wiki/Graph quotas -> one unified rerank
       -> Evidence Resolver -> Agent/Web result
```

Document lifecycle and business fact validity are separate. Replacing a file version invalidates the old Raw Evidence and derived support, but does not rewrite a business relation's `valid_from` or `valid_to`.

## Start

Use the checked-in `deploy/.env.example` as the configuration template and keep the real local values outside Git.

```bash
docker-compose -f deploy/docker-compose.yml up -d --build
```

Default local endpoints:

```text
Enterprise Web/API  http://127.0.0.1:18080/enterprise
MinIO API           http://127.0.0.1:19000
OpenSearch          http://127.0.0.1:19200
Neo4j HTTP          http://127.0.0.1:17474
Embedding/Reranker  http://127.0.0.1:18081
```

The Compose deployment does not require a local Summary LLM. Summary and Wiki compilers accept an OpenAI-compatible external provider when configured.

### First login and access control

Open `http://127.0.0.1:18080/enterprise`. If the user table is empty, the UI shows a one-time setup form that creates the first `ADMIN`. After that setup completes, public self-registration is closed: only an `ADMIN` can create, disable, re-enable, promote, demote, or reset another account from **System Management**.

The normal browser login uses an HttpOnly, SameSite session cookie. Only a SHA-256 representation of the random session value is persisted in MySQL, and account passwords are stored as bcrypt hashes. Changing or administratively resetting a password invalidates existing sessions. The last active `ADMIN` cannot be disabled or demoted.

Roles are intentionally small for V1:

- `USER`: Dashboard, Enterprise Ask, Advanced Search, Documents, Wiki, Graph, Evidence Center, `package_sources`, and a Task Center limited to that user's own ingestion jobs. Document upload is controlled by one global switch and is disabled by default.
- `ADMIN`: everything above plus global ingestion/compile Tasks, Audit Log, Distillation recommendation, System Management, user administration, and document upload regardless of the USER upload switch.

In **System Management -> Users & Access**, the `Allow USER file upload / ingestion` switch can be changed at runtime without restarting the server. When enabled, every ACTIVE `USER` gets the same Add File entry in Documents and the backend `/api/enterprise/upload` permission gate allows the request. When disabled, USER upload requests are rejected with HTTP 403 while existing documents remain unchanged. The setting is persisted in MySQL and defaults to `false` when no value has been written.

`EKB_SERVER_TOKEN` remains supported only as a compatibility service credential for automation and local operational smoke. It maps to an administrative service principal and is not the normal human login flow.

Session and upload runtime options are non-secret configuration: `EKB_SESSION_TTL_HOURS` (default `12`), `EKB_SESSION_COOKIE_SECURE` (enable behind HTTPS), `EKB_MAX_UPLOAD_MIB` (default `512`), and `EKB_MINERU_URL`.

## Managed batch ingestion

The normal enterprise upload flow is asynchronous and two-stage. A user with upload permission may drag multiple files, choose a local folder, or upload a ZIP. ZIP archives are decompressed **inside the knowledge server**, with path traversal, expanded-size, supported-type, and file-count limits enforced before any parser runs. Staged files are stored in a persistent server volume so a server restart does not silently lose queued work.

The first stage only uploads/decompresses files and creates one editable row per discovered document. Batch-level defaults for company, source department, document type, material category, document date, and tags are copied to each file, but every file can be adjusted independently before ingestion starts. The staging screen also checks existing Document/Version facts and shows one of four expected behaviors: `CREATE_DOCUMENT`, `CREATE_VERSION`, `REUSE_VERSION`, or `DUPLICATE_CONTENT`. Exact duplicate content can be switched to the existing knowledge name before the batch starts.

After the user confirms the file-level metadata, the batch is queued and a background worker executes the real pipeline. Progress is persisted as `STORING -> PARSING -> EVIDENCE -> EMBEDDING -> INDEXING -> ACTIVATING`. A failed file retains its staging source and can be retried safely; a successful file removes only its staging copy after the durable Document Version is active. `USER` accounts can see only their own ingestion tasks, while `ADMIN` sees global ingestion plus compile tasks.

Relevant non-secret limits are `EKB_MAX_BATCH_UPLOAD_MIB` (default `4096`), `EKB_MAX_BATCH_EXPANDED_MIB` (default `16384`), `EKB_MAX_BATCH_FILES` (default `2000`), and `EKB_STAGING_ROOT`. Compose mounts `/var/lib/enterprise-kb/staging` on the persistent `server-staging` volume.

## Enterprise Ask and advanced retrieval

`/enterprise/ask` is the employee-facing question-answering entry. It always runs the existing Raw/Summary/Wiki/Graph retrieval and final Evidence Resolver first; only Evidence-resolvable knowledge is eligible for answer generation. The answer model is optional: when no Ask model is configured, the endpoint remains usable in explicit `evidence_only` mode rather than pretending that a natural-language answer was generated.

When an answer model is configured, every delivered factual answer must contain valid `[n]` citations referring to the supplied Evidence-backed source numbers. Citation validation is fail-closed: missing citations or out-of-range source numbers cause the natural-language answer to be withheld and the UI falls back to Evidence-only results. The model configuration is separate from retrieval: `EKB_ASK_PROVIDER`, `EKB_ASK_BASE_URL`, `EKB_ASK_MODEL`, `EKB_ASK_REASONING_EFFORT`, `EKB_ASK_MAX_TOKENS`, and the secret `EKB_ASK_API_KEY`. The project does not hardcode an expensive answer model.

`/enterprise/search` remains the advanced diagnostic surface. It exposes knowledge type, unified rerank score, lexical hit explanations, Metadata Filters, and the exact Raw Evidence chain so retrieval quality can be inspected independently from the final answer model.

## Source viewer, Wiki, and Graph visualization

Evidence can open the exact original source through an authenticated same-origin stream. PDF sources are rendered with PDF.js and jump to the MinerU page. New PDF Evidence persists both `bbox` and `page_size` from the same MinerU coordinate system; only then does the viewer draw the Evidence highlight. Historical Evidence without `page_size` still opens the correct page but deliberately does not draw an unverified box.

The rich Enterprise Wiki and Graph views remain first-class product surfaces. Wiki keeps the encyclopedia layout, Claim/Citation visualization, local favorites, version history, and a Claim-level `vN -> vN+1` diff. Graph keeps its evidence-backed relation browser and lightweight graph visualization with bounded expansion; it is not replaced by a plain table.

## Audit trail

`/enterprise/audit` follows a compact `actor -> action -> target -> outcome` audit model. Stable actions cover authentication, user administration, upload-policy changes, single and batch ingestion, file-level metadata changes, retries, Distillation marks, Evidence source access, Enterprise Ask, and source-package creation. The ADMIN UI supports pagination and actor/action/outcome/query filters.

Audit records intentionally exclude passwords, session tokens, API keys, raw knowledge text, and the full Ask question body. Ask audit stores the search identifier and citation/evidence-only result metadata instead. Client proxy headers are ignored by default; set `EKB_TRUST_PROXY_HEADERS=true` only behind a trusted reverse proxy if the server should use `X-Forwarded-For` / `X-Real-IP` for audit IP attribution.

## Large-corpus Web behavior

Documents, Wiki, Evidence, Tasks, and Audit are server-side paginated and filtered instead of preloading hundreds of thousands of rows after login. Metadata facets are queried separately for Company / Department / Document Type / Material Category. Enterprise pages are lazy-loaded into separate frontend chunks; the heavy PDF viewer is downloaded only when a user opens an original PDF.

The Dashboard includes a **Needs Attention** area for failed Document Versions, STALE Summary/Wiki assets, failed tasks, and high-value Distillation candidates. Search shows a visible “why this matched” lexical explanation where available. The browser also keeps small local recent-question and Wiki-favorite lists for convenience; these are not authoritative server-side knowledge state.

## CLI

```bash
enterprise-kb ingest   --file <path>
enterprise-kb search   --query <text>
enterprise-kb distill  --version <active-version-id> --reason user
enterprise-kb summary  --version <active-version-id>
enterprise-kb mcp
enterprise-kb server
enterprise-kb eval --cases eval/golden/enterprise_v1_smoke.jsonl --mode A
```

## MCP

The enterprise Agent surface intentionally contains exactly two stable tools:

```text
search_knowledge
package_sources
```

`search_knowledge` merges Raw, Summary, Wiki, and bounded Graph candidates, performs one unified rerank, and applies the final Evidence Resolver gate.

`package_sources` receives Evidence IDs and creates a version-deduplicated ZIP containing the exact original source files plus `manifest.json`. Its download link is short-lived.

## Enterprise knowledge workspace

Open `/enterprise` on the knowledge server. The existing Sage-Wiki Preact/Vite/Tailwind frontend is reused; no second frontend stack is introduced. The enterprise workspace is organized around knowledge assets and provenance instead of raw database tables.

- Dashboard: real Document/Version, Raw Block, Evidence, Summary, Wiki, Graph, effective-hit, task, Grounding and Coverage health indicators. It does not invent a composite AI confidence score when no real sample exists.
- Knowledge Search: Raw/Summary/Wiki/Graph results after the unified rerank, with Metadata Filters and a visible Raw Evidence chain. The page deliberately exposes Knowledge + Evidence rather than pretending the retrieval service produced the final Agent answer.
- Documents: searchable Version lifecycle, metadata, Summary quality gates, effective usage, Version history, and ADMIN-only high-value Distillation recommendation. ADMIN can always upload a file with enterprise metadata; USER gets the same upload entry when the global USER upload switch is enabled. The Web path calls the real MinerU -> Raw Block -> Embedding -> OpenSearch ingestion pipeline rather than a browser-only staging flow.
- Enterprise Wiki: encyclopedia-style Claim reading, section navigation, Fact references, Grounding/Temporal status, and Raw Evidence citations.
- Graph: a relation browser plus a lightweight graph view over bounded 1-2 hop relations with ACTIVE Evidence Support.
- Evidence Center: searchable Raw provenance, exact Version/location/excerpt, active-state filtering, and multi-Evidence source-package delivery.
- Tasks: ADMIN-only compile-task state, attempts, timestamps and failures. The V1 UI is intentionally read-only here rather than offering a retry action without an explicit idempotency contract.
- System Management: ADMIN-only account lifecycle, roles, runtime capability wiring, storage responsibilities, and trust-contract visibility. Secrets and connection credentials are never returned by this page.
- Evidence drawer: exact document/version, object key, page/block/bbox location, Raw content, and copy support.

Enterprise sub-routes (`/enterprise/search`, `/enterprise/documents`, `/enterprise/wiki`, `/enterprise/graph`, `/enterprise/evidence`, `/enterprise/tasks`, and `/enterprise/admin`) use the same SPA fallback and can be opened directly.

## Distillation recommendation

A source is recommended for high-value compilation when any of these conditions is met:

1. A user explicitly marks the ACTIVE Version.
2. An existing question is grounded in that Raw Version.
3. Runtime delivery uses that Version as final Raw Evidence in at least three distinct searches.

BM25 candidates, Vector candidates, Summary candidates, Wiki candidates, and Graph candidates do not increment heat. Evaluation traffic also disables effective-hit auditing.

## Retrieval A/B

`enterprise-kb eval` implements the fixed V1 modes:

```text
A = Raw only
B = Raw + Summary
C = Raw + Summary + Wiki
D = Full Graph
```

The direct retrieval harness reports Knowledge Recall@K, Evidence Recall@K, retrieval success rate, mean latency, and p95 latency. It explicitly marks Agent Task Success unavailable because direct retrieval success is not Agent Task Success.

`eval/golden/enterprise_v1_smoke.jsonl` contains 30 smoke cases for the current acceptance sample. It validates the evaluation machinery and Raw baseline only. It is not the final enterprise-corpus benchmark and must not be used to claim Summary/Wiki/Graph uplift.

### Compiled-artifact quality evaluation

`enterprise-kb artifact-eval --cases <artifact-golden.jsonl>` reads the **actual persisted compiled artifacts from MySQL** for each exact Version scope and reports Summary Fact Precision/Recall, Graph Relation Precision/Recall, Entity Resolution Accuracy, Wiki Claim Precision/Recall, and Wiki Claim Grounding Rate. The Golden file contains expectations only; predicted facts, relations, mappings, and claims are never copied into the Golden input.

One JSONL case has this shape:

```json
{"id":"case-001","version_ids":["<version-uuid>"],"expected_summary_facts":[{"text":"required fact","alternatives":["approved paraphrase"]}],"expected_relations":[{"source":"Canonical A","relation_type":"CONTROLS","target":"Canonical B"}],"expected_entity_mappings":[{"surface_form":"A Co.","entity_type":"COMPANY","canonical_name":"Canonical A"}],"expected_wiki_claims":[{"text":"required wiki claim"}]}
```

Text matching is deterministic after case/whitespace normalization. Human-approved paraphrases must be listed explicitly in `alternatives`; the evaluator does not call an LLM judge. A metric with no Golden expectation is serialized as `available=false` with null numeric values instead of a fabricated zero or one. Agent Task Success and average Retrieval Round are also explicitly unavailable in this artifact evaluator because they require an Agent-level benchmark.

## Current model policy

Embedding and Reranker remain local by default. The previous local llama.cpp Summary Generator/Judge deployment was removed because CPU inference was not operationally useful. The compiler model interfaces support custom OpenAI Base URLs plus per-stage model/provider/reasoning settings.

For a remote gateway that implements native OpenAI `response_format=json_schema`, set the enterprise LLM provider to `openai` even when the Base URL is not api.openai.com. Keep `openai-compatible` for self-hosted backends that intentionally use the plain JSON fallback path. Generator, Judge, Graph extractor, Wiki writer, and Wiki judge can override the provider independently; `reasoning_effort` is also configurable globally or per stage.

The lightweight external-provider smoke currently verified `gpt-5.4-mini` as Generator/Writer and `gpt-5.4` as the independent Judge/strong Graph model. That smoke produced an ACTIVE Grounded+Covered Summary, evidence-backed Graph relations, an ACTIVE Claim-based Wiki, and a successful Full (D) unified-retrieval query. It proves runtime compatibility only, not production-corpus quality. `gpt-5.6-luna` appeared in the provider model list but the gateway returned transient `503 no available token` during this smoke, so it was not used to claim acceptance; it can be retried later, including a configured `reasoning_effort=max`, when gateway capacity is available.

## Verification commands

```bash
# Go enterprise packages and CLI
docker build -f deploy/Dockerfile.verify -t enterprise-kb-verify:local .
docker run --rm enterprise-kb-verify:local go test -count=1 ./internal/enterprise/... ./cmd/enterprise-kb

# Web typecheck + production build
npm --prefix web ci
npm --prefix web run build

# Compose syntax
docker-compose -f deploy/docker-compose.yml config --quiet
```

The production `deploy/Dockerfile` is multi-stage: Node builds the existing Preact UI, Go builds `enterprise-kb`, and the final Alpine image contains only runtime certificates/timezone data, the binary, and static web assets.
