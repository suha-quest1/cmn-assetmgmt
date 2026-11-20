# Worker: finalizeResponses

- Worker id (manifest `source.workerId`): `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#finalizeResponses`
- Activity name: `FinalizeResponses`
- Language/SDK: java/camunda-client
- Registration: src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:70 — `handle("finalizeResponses", <handler>)`
- autoComplete: false (explicit completion)

## Classification

- Side-effect class: **non-idempotent** (confidence 0.8, provenance llm)
- Summary: The worker closes the LP response window for a capital call, marks outstanding LPs as defaulted, logs an audit event, and returns the LP responses.
- Rationale: The worker runs a SELECT, then an UPDATE that sets status to 'defaulted' for rows where status is not in ('committed','deferred'). That UPDATE is repeatable, because a second run matches no new rows. But the worker also runs 'INSERT INTO call_events (call_id, lp_id, step, detail) VALUES ($1,$2,$3,$4)' through Handlers.event. This INSERT has no ON CONFLICT clause, no unique key and no keyed lookup. Each retry adds a duplicate audit row. Handlers.event also catches SQLException and only logs it, so a failed insert is lost without notice. The returned lpResponses can also change between runs, because the SELECT runs before the UPDATE and a retry reads the already defaulted rows. The key is one run per call, so callId is enough. The slice shows no per-LP or per-stage dimension for this step.

## Migration guidance

- Coverage: **ASSISTED**
- Suggested retry policy: maxAttempts=3
- **Suggested idempotency key: `callId:finalizeResponses`** — a target to implement, NOT a mechanism that exists. Apply it where this activity touches the outside world: an `Idempotency-Key` header on an outbound request, a keyed insert, or a lookup before a send. If you cannot, write why in `gaps.md` and name this key — never say the analysis supplied none.

## Variables

Read:

Written:

## Callee source — READ THESE TOO

The handler delegates its real work to the code below. Read each file, follow it
further if it delegates again (service → client → http), and translate that logic
into the activity body — do NOT stub a success return.

- `src/main/java/com/vishworks/assetmgmt/camunda/JobWorkers.java:85-91` — `com.vishworks.assetmgmt.camunda.Job.bind`
- `src/main/java/com/vishworks/assetmgmt/camunda/JobWorkers.java:94-98` — `com.vishworks.assetmgmt.camunda.Job.header`
- `src/main/java/com/vishworks/assetmgmt/db/Db.java:191-199` — `com.vishworks.assetmgmt.db.Db.query`
- `src/main/java/com/vishworks/assetmgmt/db/Db.java:171-175` — `com.vishworks.assetmgmt.db.Db.exec`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:91-98` — `com.vishworks.assetmgmt.handlers.Handlers.event`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:186-192` — `com.vishworks.assetmgmt.handlers.Handlers.m`

## Source

Read the handler source with read_file: `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java` (source repo root).
Call graph: Job.bind, Job.header, Db.query, Db.exec, Handlers.event, Handlers.m
