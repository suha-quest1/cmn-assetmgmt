# Worker: predictDefaultRisk

- Worker id (manifest `source.workerId`): `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#predictDefaultRisk`
- Activity name: `PredictDefaultRisk`
- Language/SDK: java/camunda-client
- Registration: src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:72 — `handle("predictDefaultRisk", <handler>)`
- autoComplete: false (explicit completion)

## Classification

- Side-effect class: **non-idempotent** (confidence 0.7, provenance llm)
- Summary: The worker gets risk scores for LPs from an ML scorer, saves them to the database, sets the call status, and logs an audit event.
- Rationale: The worker calls postJSON to POST /score on the ML scorer. This call has no idempotency header and the scorer is probably a pure computation, but the code does not show it. The score UPDATE is keyed by call_id and lp_id and sets a fixed value, so a retry is repeatable. The status UPDATE in setCallStatus is also repeatable. But event() runs 'INSERT INTO call_events (call_id, lp_id, step, detail) VALUES (...)' with no ON CONFLICT clause and no unique key that the code shows. Each retry adds one more audit row. No deduplication is visible, so the class is non-idempotent. The worker runs once per call, so callId is the key to suggest.
- **Requires human confirmation** — treat the translation as a draft.

## Migration guidance

- Coverage: **ASSISTED**
- Suggested retry policy: maxAttempts=3
- **Suggested idempotency key: `callId`** — a target to implement, NOT a mechanism that exists. Apply it where this activity touches the outside world: an `Idempotency-Key` header on an outbound request, a keyed insert, or a lookup before a send. If you cannot, write why in `gaps.md` and name this key — never say the analysis supplied none.

## Variables

Read:

Written:

## External calls

- service-call: `Handlers.postJSON` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:451 — side effect suspected

## Callee source — READ THESE TOO

The handler delegates its real work to the code below. Read each file, follow it
further if it delegates again (service → client → http), and translate that logic
into the activity body — do NOT stub a success return.

- `src/main/java/com/vishworks/assetmgmt/camunda/JobWorkers.java:85-91` — `com.vishworks.assetmgmt.camunda.Job.bind`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:100-122` — `com.vishworks.assetmgmt.handlers.Handlers.postJSON`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:186-192` — `com.vishworks.assetmgmt.handlers.Handlers.m`
- `src/main/java/com/vishworks/assetmgmt/util/Errors.java:22-33` — `com.vishworks.assetmgmt.util.Errors.message`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:435-437` — `com.vishworks.assetmgmt.handlers.Handlers.nz`
- `src/main/java/com/vishworks/assetmgmt/db/Db.java:171-175` — `com.vishworks.assetmgmt.db.Db.exec`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:177-183` — `com.vishworks.assetmgmt.handlers.Handlers.setCallStatus`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:91-98` — `com.vishworks.assetmgmt.handlers.Handlers.event`

## Source

Read the handler source with read_file: `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java` (source repo root).
Call graph: Job.bind, Handlers.postJSON, Handlers.m, Errors.message, Handlers.nz, Db.exec, Handlers.setCallStatus, Handlers.event
