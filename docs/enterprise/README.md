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

## Web evidence workbench

Open `/enterprise` on the knowledge server. The existing Sage-Wiki Preact/Vite frontend is reused; the enterprise additions are deliberately limited to evidence-oriented V1 views.

- Documents: Document/Version lifecycle, metadata, Summary Grounding/Coverage state, effective-hit count, and manual Distillation recommendation.
- Wiki Evidence: Wiki Claim -> Summary Fact -> Raw Evidence references.
- Graph Evidence: bounded 1-2 hop Relation results with ACTIVE Evidence Support.
- Evidence drawer: exact document/version, object key, page/block/bbox location, and Raw content.

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

Embedding and Reranker remain local by default. The previous local llama.cpp Summary Generator/Judge deployment was removed because CPU inference was not operationally useful. The compiler model interfaces remain OpenAI-compatible. Gate 2/3 quality acceptance must be repeated with the external provider before those gates are declared complete.

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
