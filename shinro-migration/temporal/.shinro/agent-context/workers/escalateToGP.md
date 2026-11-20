# Worker: escalateToGP

- Worker id (manifest `source.workerId`): `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#escalateToGP`
- Activity name: `EscalateToGP`
- Language/SDK: java/camunda-client
- Registration: src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:75 — `handle("escalateToGP", <handler>)`
- autoComplete: false (explicit completion)

## Classification

- Side-effect class: **non-idempotent** (confidence 0.85, provenance llm)
- Summary: The worker escalates a high-risk LP to the GP committee. It sends a notification, records the escalation, sets the call status, and logs an event.
- Rationale: The worker sends a notification with Handlers.email. This calls postJSON, a plain POST to sesUrl + '/send' with only a Content-Type header and no idempotency header, so a retry sends a duplicate message. Handlers.event runs a plain INSERT INTO call_events with no ON CONFLICT, so a retry adds a duplicate row. The INSERT into gp_escalations uses ON CONFLICT (call_id, lp_id) DO NOTHING and the status UPDATE is repeatable, but they do not make the email or the event safe to retry. Both email and event also swallow their errors. A failed send does not trigger a retry and can be lost silently. The worker is non-idempotent because of the email POST and the event INSERT.

## Migration guidance

- Coverage: **ASSISTED**
- Suggested retry policy: maxAttempts=3
- **Suggested idempotency key: `callId:lpId`** — a target to implement, NOT a mechanism that exists. Apply it where this activity touches the outside world: an `Idempotency-Key` header on an outbound request, a keyed insert, or a lookup before a send. If you cannot, write why in `gaps.md` and name this key — never say the analysis supplied none.

## Variables

Read:

Written:

## External calls

- service-call: `Handlers.email` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:546 — side effect suspected

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
