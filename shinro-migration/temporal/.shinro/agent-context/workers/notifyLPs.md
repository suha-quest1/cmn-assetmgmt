# Worker: notifyLPs

- Worker id (manifest `source.workerId`): `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#notifyLPs`
- Activity name: `NotifyLPs`
- Language/SDK: java/camunda-client
- Registration: src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:67 — `handle("notifyLPs", <handler>)`
- autoComplete: false (explicit completion)

## Classification

- Side-effect class: **non-idempotent** (confidence 0.9, provenance llm)
- Summary: The worker sends a capital call email to each LP, marks the LP rows as notified, sets the call status to awaiting_lps, and logs an event.
- Rationale: For each LP, email() calls postJSON(sesUrl + "/send", payload, null). This is a plain POST with only a Content-Type header. It has no idempotency header and no store lookup that gates the send. The callId and lpId values appear only in the body, so they do not deduplicate. A retry sends the LP emails again. The email() hop also catches IOException and logs it, so a failed send is swallowed and the job still completes. The event() hop does a plain INSERT INTO call_events with no ON CONFLICT, so a retry adds a duplicate event row. The UPDATE on capital_call_lps is guarded by status='issued' and the setCallStatus UPDATE sets a fixed value. These two writes are safe to repeat, but the email send and the event insert are not.

## Migration guidance

- Coverage: **ASSISTED**
- Suggested retry policy: maxAttempts=3
- **Suggested idempotency key: `callId:lpId`** — a target to implement, NOT a mechanism that exists. Apply it where this activity touches the outside world: an `Idempotency-Key` header on an outbound request, a keyed insert, or a lookup before a send. If you cannot, write why in `gaps.md` and name this key — never say the analysis supplied none.

## Variables

Read:

Written:

## External calls

- service-call: `Handlers.email` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:290 — side effect suspected

## Callee source — READ THESE TOO

The handler delegates its real work to the code below. Read each file, follow it
further if it delegates again (service → client → http), and translate that logic
into the activity body — do NOT stub a success return.

- `src/main/java/com/vishworks/assetmgmt/camunda/JobWorkers.java:85-91` — `com.vishworks.assetmgmt.camunda.Job.bind`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:157-166` — `com.vishworks.assetmgmt.handlers.Handlers.email`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:186-192` — `com.vishworks.assetmgmt.handlers.Handlers.m`
- `src/main/java/com/vishworks/assetmgmt/db/Db.java:171-175` — `com.vishworks.assetmgmt.db.Db.exec`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:177-183` — `com.vishworks.assetmgmt.handlers.Handlers.setCallStatus`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:91-98` — `com.vishworks.assetmgmt.handlers.Handlers.event`

## Source

Read the handler source with read_file: `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java` (source repo root).
Call graph: Job.bind, Handlers.email, Handlers.m, Db.exec, Handlers.setCallStatus, Handlers.event
