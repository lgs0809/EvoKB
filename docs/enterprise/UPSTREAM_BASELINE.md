# Enterprise Knowledge Base Upstream Baseline

This repository is the enterprise knowledge-base fork based on Sage-Wiki.

## Pinned upstream

- upstream_repo: `https://github.com/xoai/sage-wiki.git`
- upstream_commit: `42f741c05688ae5efd80381094fe0a0569e5a22b`
- fork_base_date: `2026-08-13`
- our_branch: `main`
- upstream_remote: `upstream`

The upstream commit is intentionally pinned. Enterprise changes must not silently track a moving upstream `main`.

## Baseline verification on 2026-08-13

Verified against the pinned upstream source without enterprise modifications:

- `docker build -t enterprise-knowledge-base:sage-upstream .` completed successfully.
- The final image is `enterprise-knowledge-base:sage-upstream`.
- `sage-wiki --help` runs successfully from the image.
- `sage-wiki init` created a real project with `.sage/wiki.db`, `.manifest.json`, `raw/`, `wiki/`, and `config.yaml`.
- The original Web UI started from the pinned image and returned HTTP 200 on local port `13333`.
- `GET /v1/status` returned a valid upstream status payload from the initialized project.
- The first full `go test ./...` run exited 1 after about 10m49s because `internal/compiler` hit Go's default 10-minute package timeout on this Windows/Docker VM; it was not a compile error.
- Re-running the pinned upstream `internal/compiler` package with `go test -timeout 30m ./internal/compiler` exited 0. The enterprise regression command therefore uses an explicit extended package timeout on this machine rather than treating the environmental timeout as a source failure.

A real upstream LLM compile is deliberately not replaced by a mock model. The generated upstream project currently has no configured real LLM credential/local model on this machine, so LLM-dependent `compile` validation remains an explicit external-model prerequisite rather than a fabricated pass.

## Recovered MinerU Pipeline

The previously deployed MinerU was recovered at:

`C:\Users\101201912008\Documents\Codex\2026-07-30\zai\work\mineru`

It is a local Windows/Python deployment, not a Docker container.

- MinerU version: `3.4.3`
- API: `http://127.0.0.1:8000`
- UI: `http://127.0.0.1:7860`
- backend used by this project: `pipeline`
- model source: local
- async API: `POST /tasks`, `GET /tasks/{task_id}`, `GET /tasks/{task_id}/result`
- sync API: `POST /file_parse`

Real smoke tests passed for both a normal PDF and an OCR/image-based PDF. The normal PDF produced Markdown, content-list JSON, middle JSON, model JSON and extracted images. The OCR smoke task completed with `error=null` using `backend=pipeline` and `parse_method=ocr`.

The historical `parse-file.ps1` wrapper has incorrect `$LASTEXITCODE` handling and can report a blank failure while the API task succeeds. Enterprise integration therefore uses the MinerU HTTP API directly and does not depend on that wrapper.

## Baseline preservation rule

The upstream SQLite/PostgreSQL paths remain in the repository as comparison and regression paths. Enterprise storage adapters are added alongside them; they are not removed while the enterprise path is being brought up.
