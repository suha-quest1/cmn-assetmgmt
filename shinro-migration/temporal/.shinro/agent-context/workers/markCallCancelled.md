# Worker: markCallCancelled

- Worker id (manifest `source.workerId`): `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#markCallCancelled`
- Activity name: `MarkCallCancelled`
- Language/SDK: java/camunda-client
- Registration: src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:79 — `handle("markCallCancelled", <handler>)`
- autoComplete: false (explicit completion)

## Classification

- Side-effect class: **non-idempotent** (confidence 0.9, provenance llm)
- Summary: The worker cancels a capital call. It updates the call and LP statuses in the database, emails each LP a cancellation notice, and logs an event.
- Rationale: The two status updates are repeatable. They set fixed values with WHERE call_id=$1, and the call update also sets updated_at=NOW(). But the worker also sends one email per LP through Handlers.email. This is a plain POST to sesUrl + "/send" in Handlers.postJSON, with no Idempotency-Key header and no deduplication check. A retry sends duplicate cancellation emails. Handlers.event runs a plain INSERT INTO call_events with no ON CONFLICT or unique key, so a retry adds duplicate event rows. The email and event helpers catch their errors and only log them, so a failed send is lost and never retried. A callId or lpId in the payload or the SQL args is not deduplication.

## Migration guidance

- Coverage: **ASSISTED**
- Suggested retry policy: maxAttempts=3
- **Suggested idempotency key: `callId:lpId`** — a target to implement, NOT a mechanism that exists. Apply it where this activity touches the outside world: an `Idempotency-Key` header on an outbound request, a keyed insert, or a lookup before a send. If you cannot, write why in `gaps.md` and name this key — never say the analysis supplied none.

## Variables

Read:

Written:

## External calls

- service-call: `Handlers.email` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:657 — side effect suspected

## Callee source — READ THESE TOO

The handler delegates its real work to the code below. Read each file, follow it
further if it delegates again (service → client → http), and translate that logic
into the activity body — do NOT stub a success return.

- `src/main/java/com/vishworks/assetmgmt/camunda/JobWorkers.java:85-91` — `com.vishworks.assetmgmt.camunda.Job.bind`
- `src/main/java/com/vishworks/assetmgmt/db/Db.java:171-175` — `com.vishworks.assetmgmt.db.Db.exec`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:177-183` — `com.vishworks.assetmgmt.handlers.Handlers.setCallStatus`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:435-437` — `com.vishworks.assetmgmt.handlers.Handlers.nz`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:157-166` — `com.vishworks.assetmgmt.handlers.Handlers.email`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:186-192` — `com.vishworks.assetmgmt.handlers.Handlers.m`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:91-98` — `com.vishworks.assetmgmt.handlers.Handlers.event`

## Source

Read the handler source with read_file: `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java` (source repo root).
Call graph: Job.bind, Db.exec, Handlers.setCallStatus, Handlers.nz, Handlers.email, Handlers.m, Handlers.event
