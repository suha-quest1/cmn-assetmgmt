# Worker: aggregateLiquidity

- Worker id (manifest `source.workerId`): `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#aggregateLiquidity`
- Activity name: `AggregateLiquidity`
- Language/SDK: java/camunda-client
- Registration: src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:71 — `handle("aggregateLiquidity", <handler>)`
- autoComplete: false (explicit completion)

## Classification

- Side-effect class: **non-idempotent** (confidence 0.8, provenance llm)
- Summary: The worker sums committed LP amounts, computes the funding gap, updates the capital call row, and logs an audit event.
- Rationale: The UPDATE on capital_calls is keyed by call_id and sets absolute values computed from the input. A retry with the same input gives the same row state, so this write is repeatable. But the worker also runs 'INSERT INTO call_events (call_id, lp_id, step, detail) VALUES ($1,$2,$3,$4)' through Handlers.event. This INSERT has no ON CONFLICT clause and no visible unique constraint. Each retry adds a duplicate audit row. Handlers.event also catches SQLException and only logs it, so no deduplication exists. A where-clause key on the UPDATE is not deduplication of the INSERT. The class is therefore non-idempotent. The workflow runs this worker once per capital call, so callId identifies the operation.

## Migration guidance

- Coverage: **ASSISTED**
- Suggested retry policy: maxAttempts=3
- **Suggested idempotency key: `callId`** — a target to implement, NOT a mechanism that exists. Apply it where this activity touches the outside world: an `Idempotency-Key` header on an outbound request, a keyed insert, or a lookup before a send. If you cannot, write why in `gaps.md` and name this key — never say the analysis supplied none.

## Variables

Read:

Written:

## Callee source — READ THESE TOO

The handler delegates its real work to the code below. Read each file, follow it
further if it delegates again (service → client → http), and translate that logic
into the activity body — do NOT stub a success return.

- `src/main/java/com/vishworks/assetmgmt/camunda/JobWorkers.java:85-91` — `com.vishworks.assetmgmt.camunda.Job.bind`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:435-437` — `com.vishworks.assetmgmt.handlers.Handlers.nz`
- `src/main/java/com/vishworks/assetmgmt/db/Db.java:171-175` — `com.vishworks.assetmgmt.db.Db.exec`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:91-98` — `com.vishworks.assetmgmt.handlers.Handlers.event`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:186-192` — `com.vishworks.assetmgmt.handlers.Handlers.m`

## Source

Read the handler source with read_file: `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java` (source repo root).
Call graph: Job.bind, Handlers.nz, Db.exec, Handlers.event, Handlers.m
