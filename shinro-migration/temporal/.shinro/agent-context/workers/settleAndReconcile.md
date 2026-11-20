# Worker: settleAndReconcile

- Worker id (manifest `source.workerId`): `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#settleAndReconcile`
- Activity name: `SettleAndReconcile`
- Language/SDK: java/camunda-client
- Registration: src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:77 — `handle("settleAndReconcile", <handler>)`
- autoComplete: false (explicit completion)

## Classification

- Side-effect class: **non-idempotent** (confidence 0.5, provenance llm)
- Summary: The worker settles a capital call. It rebuilds the double-entry ledger, updates LP commitment rows, marks the call as settled, and logs an audit event.
- Rationale: All ledger writes run in one transaction (setAutoCommit(false), commit, rollback on error). The transaction starts with 'DELETE FROM ledger_entries WHERE call_id=$1'. This removes the rows from any earlier attempt before it inserts them again. The UPDATE statements on capital_call_lps and capital_calls are also keyed by call_id (and lp_id). A retry therefore rebuilds the same state and does not double-book wires. The last step, Handlers.event, runs a plain 'INSERT INTO call_events' with no deduplication, outside the transaction. A retry after this step adds a duplicate audit row. This is a minor effect on a log table, but a person should confirm that it is acceptable. The source of the connection (Db.getConnection, HikariDataSource) is not resolved. It does not change the result. [auto-downgraded from idempotent-with-key: the claimed key 'callId' is not backed by an in-code dedup mechanism the model could verify, so retries cannot be assumed safe]
- **Requires human confirmation** — treat the translation as a draft.
- **Unaccounted call receiver(s):** `HikariDataSource` — not in the analyzed repository and not a known-pure type, so the analysis could NOT prove this worker is side-effect free. Do not generate it as a plain retryable read; keep the call shape and let the review stop stand.

## Migration guidance

- Coverage: **ASSISTED**
- Suggested retry policy: maxAttempts=3

## Variables

Read:

Written:

## Callee source — READ THESE TOO

The handler delegates its real work to the code below. Read each file, follow it
further if it delegates again (service → client → http), and translate that logic
into the activity body — do NOT stub a success return.

- `src/main/java/com/vishworks/assetmgmt/camunda/JobWorkers.java:85-91` — `com.vishworks.assetmgmt.camunda.Job.bind`
- `src/main/java/com/vishworks/assetmgmt/db/Db.java:113-115` — `com.vishworks.assetmgmt.db.Db.getConnection`
- `src/main/java/com/vishworks/assetmgmt/db/Db.java:171-175` — `com.vishworks.assetmgmt.db.Db.exec`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:435-437` — `com.vishworks.assetmgmt.handlers.Handlers.nz`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:91-98` — `com.vishworks.assetmgmt.handlers.Handlers.event`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:186-192` — `com.vishworks.assetmgmt.handlers.Handlers.m`

## Source

Read the handler source with read_file: `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java` (source repo root).
Call graph: Job.bind, Db.getConnection, Db.exec, Handlers.nz, Handlers.event, Handlers.m
