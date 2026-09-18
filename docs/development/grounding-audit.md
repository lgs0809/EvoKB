# Fact grounding and publication audit

Summary and Wiki share `GroundingConstraintAudit`. A model's `supported=true` is insufficient to publish a proposition. The judge must locate its subject, action, object, polarity, conditions, scope and time in the candidate and supplied evidence. Missing or duplicate checks, invented quotations and malformed responses abort the compilation; a valid `UNKNOWN` or `CONTRADICTED` check refuses the candidate. Existing independent coverage checks and atomic source/ACL publication fences remain authoritative.

The seven checks are model judgments, not a formal semantic proof. Deterministic validation establishes that the reported spans actually occur in the supplied candidate/evidence and that the complete check contract was fulfilled. Further corpus evaluation remains necessary. The subject-reversal diagnostic is a retained regression case, not a quality score.

An explicitly enabled assistant extractive fallback may certify only an entire identical source. It cannot approve a rewritten claim, a source substring, omitted conditions or a partial selection of a multi-source context. The default fallback remains disabled. Existing complete Raw projections for coverage repair now retain the same audit policy and exact-copy proof; their content and coverage behavior are unchanged.

V21 adds `grounding_reason` and a nullable structured `grounding_audit` to Summary facts and Wiki claims. The existing authorized publication transaction writes a typed policy version, complete candidate, support verdict and every located check. Old rows keep their original statuses, empty reason and null audit, which means historical/unreviewed, not a newly passed audit. Internal quotations are not added to public/low-clearance DTOs or application logs.

The same current-audit validator governs Summary checkpoints, fresh Concept/Graph/Wiki inputs and Wiki keyword projection. Reuse requires every fact's complete typed audit, the current policy, matching candidate and actual source quotations; a reason that merely mentions the policy is insufficient. All retained entry members and model dependencies must exist and pass this check; missing members cannot be silently dropped from a new projection. New Summary identity and durable generation keys include the policy. An explicit normal generation command rebuilds historical summaries without replacing old history or manufacturing completed tasks. Historical source permissions remain unchanged; this migration does not automatically rebuild the whole library.

Historical entry metadata is a cache, not new evidence. If its dependencies have no current audit, the native catalogue uses its existing identity-only candidate view: the stable identity snapshot remains available, but old prose, unproven aliases and unaudited retained members do not enter the model. Normal compilation from audited sources may rebuild that same entry ID through the ordinary ownership, revision and source/ACL publication fences. A selected fresh result containing missing or unreviewed dependencies still fails closed. Background source association also requires current audits before reusing entry metadata. Native audit checks read facts and Raw evidence from PostgreSQL, without a remote markdown/ObjectStore read inside the publication transaction.

## Reproducing component verification

Use a fresh output stem for every attempt. The scripts preserve failed attempts and freeze the bytes compiled; private credentials are loaded from the ignored `.env.target` and are not printed.

```sh
python3 scripts/test-grounding-focused-junit.py \
  --jar /absolute/path/to/verified-pre-V21/evokb.jar \
  --include-compilation --output /private/evidence/grounding.json

python3 scripts/test-postgres-grounding-audit.py \
  --jar-path /absolute/path/to/verified-pre-V21/evokb.jar \
  --classes-path /private/evidence/grounding.frozen/classes \
  --output /private/evidence/postgres-audit.json
```

The PostgreSQL test creates and retains a separate randomly named schema, applies the old artifact migrations, creates explicitly synthetic historical records, then applies candidate V21. It uses the actual compilation repository/publication to test roundtrip, rollback, ACL changes, authorized DTO visibility and a rejected `UNKNOWN` compilation. It also exercises a historical ACTIVE/PASS but unaudited entry: sanitized candidates enter the actual Concept compiler, a normal claimed task rebuilds the same stable ID from an independently audited synthetic source, old fact/build-input history stays intact, and selected unaudited or missing dependencies are refused. ObjectStore reads in native publication/audit transactions are forbidden by the probe. Its model, embedding, index and object-store fixtures are synthetic. It is not a deployed API, MinIO, Milvus or browser acceptance test.

Full release verification/deployment continues through `scripts/verify-backend.py` and the normal `scripts/deploy-target.py` guard. A source-overlay component report cannot satisfy the deployment guard or label the previous release as the new source.

## Read-only deployed inspection

`deploy/acceptance/grounding-audit-inspection.sql` reports audit availability, statuses, generation history and derived dependencies for a chosen source version. It intentionally avoids displaying source text and quoted internal audit content. Run it only with the normal administrator's local DB access. The DB's metadata remains authoritative; no inspection query changes statuses or simulates a successful workflow.
