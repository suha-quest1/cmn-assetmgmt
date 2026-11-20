# Worker: recordLPResponse

- Worker id (manifest `source.workerId`): `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#recordLPResponse`
- Activity name: `RecordLPResponse`
- Language/SDK: java/camunda-client
- Registration: src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:69 — `handle("recordLPResponse", <handler>)`
- autoComplete: false (explicit completion)

## Classification

- Side-effect class: **non-idempotent** (confidence 0.7, provenance llm)
- Summary: The worker records one LP response to a capital call, updates the LP row and the call totals, and logs an audit event.
- Rationale: The main write is an UPDATE of capital_call_lps keyed by call_id and lp_id. It sets absolute values, so a repeat gives the same row state. The refreshAggregates call recomputes a SUM from the LP rows, so it is also repeatable. But the event call runs a plain INSERT INTO call_events with no ON CONFLICT and no unique key that the code shows. Each retry adds a duplicate audit row. The event insert also swallows SQLException, so the failure is hidden. The code shows no deduplication for the insert, so the worker is non-idempotent by default. The key should name the call, the LP and the follow-up round, because the worker runs once per LP and the detail text changes with followUps.

## Migration guidance

- Coverage: **ASSISTED**
- Suggested retry policy: maxAttempts=3
- **Suggested idempotency key: `callId:lpId:followUps`** — a target to implement, NOT a mechanism that exists. Apply it where this activity touches the outside world: an `Idempotency-Key` header on an outbound request, a keyed insert, or a lookup before a send. If you cannot, write why in `gaps.md` and name this key — never say the analysis supplied none.

## Variables

Read:

Written:

## Callee source — READ THESE TOO

The handler delegates its real work to the code below. Read each file, follow it
further if it delegates again (service → client → http), and translate that logic
into the activity body — do NOT stub a success return.

- `src/main/java/com/vishworks/assetmgmt/camunda/JobWorkers.java:85-91` — `com.vishworks.assetmgmt.camunda.Job.bind`
- `src/main/java/com/vishworks/assetmgmt/camunda/JobWorkers.java:94-98` — `com.vishworks.assetmgmt.camunda.Job.header`
- `src/main/java/com/vishworks/assetmgmt/db/Db.java:171-175` — `com.vishworks.assetmgmt.db.Db.exec`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:168-175` — `com.vishworks.assetmgmt.handlers.Handlers.refreshAggregates`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:91-98` — `com.vishworks.assetmgmt.handlers.Handlers.event`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:186-192` — `com.vishworks.assetmgmt.handlers.Handlers.m`

## Source

Read the handler source with read_file: `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java` (source repo root).
Call graph: Job.bind, Job.header, Db.exec, Handlers.refreshAggregates, Handlers.event, Handlers.m
