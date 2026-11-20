# Worker: triggerBridge

- Worker id (manifest `source.workerId`): `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#triggerBridge`
- Activity name: `TriggerBridge`
- Language/SDK: java/camunda-client
- Registration: src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:74 — `handle("triggerBridge", <handler>)`
- autoComplete: false (explicit completion)

## Classification

- Side-effect class: **non-idempotent** (confidence 0.9, provenance llm)
- Summary: The worker draws down a credit line to bridge a capital call gap, then records the confirmation in the database and logs an event.
- Rationale: The worker calls postJSON to creditUrl + "/drawdown". This is a plain HTTP POST with only a Content-Type header. The code shows no Idempotency-Key header and no dedup check before the call. The callId and gapUSD values appear only in the request body, and that is not deduplication. A retry after a lost response can draw down the credit line twice. The UPDATE on capital_calls is repeatable, but it runs after the POST. The INSERT into call_events has no unique key or ON CONFLICT clause, so a retry adds a duplicate event row. The worker reads one callId per invocation, so callId alone identifies the operation.

## Migration guidance

- Coverage: **ASSISTED**
- Suggested retry policy: maxAttempts=3
- **Suggested idempotency key: `callId`** — a target to implement, NOT a mechanism that exists. Apply it where this activity touches the outside world: an `Idempotency-Key` header on an outbound request, a keyed insert, or a lookup before a send. If you cannot, write why in `gaps.md` and name this key — never say the analysis supplied none.

## Variables

Read:

Written:

## External calls

- service-call: `Handlers.postJSON` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:519 — side effect suspected

## Callee source — READ THESE TOO

The handler delegates its real work to the code below. Read each file, follow it
further if it delegates again (service → client → http), and translate that logic
into the activity body — do NOT stub a success return.

- `src/main/java/com/vishworks/assetmgmt/camunda/JobWorkers.java:85-91` — `com.vishworks.assetmgmt.camunda.Job.bind`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:100-122` — `com.vishworks.assetmgmt.handlers.Handlers.postJSON`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:186-192` — `com.vishworks.assetmgmt.handlers.Handlers.m`
- `src/main/java/com/vishworks/assetmgmt/util/Errors.java:22-33` — `com.vishworks.assetmgmt.util.Errors.message`
- `src/main/java/com/vishworks/assetmgmt/db/Db.java:171-175` — `com.vishworks.assetmgmt.db.Db.exec`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:91-98` — `com.vishworks.assetmgmt.handlers.Handlers.event`

## Source

Read the handler source with read_file: `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java` (source repo root).
Call graph: Job.bind, Handlers.postJSON, Handlers.m, Errors.message, Db.exec, Handlers.event
