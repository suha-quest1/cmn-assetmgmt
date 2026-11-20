# Worker: applyGPDecision

- Worker id (manifest `source.workerId`): `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#applyGPDecision`
- Activity name: `ApplyGPDecision`
- Language/SDK: java/camunda-client
- Registration: src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:76 — `handle("applyGPDecision", <handler>)`
- autoComplete: false (explicit completion)

## Classification

- Side-effect class: **non-idempotent** (confidence 0.9, provenance llm)
- Summary: The worker applies a GP decision on a high-risk LP in a capital call. It sends enforcement and decision emails, updates two status tables, and logs an event.
- Rationale: Handlers.email sends a plain POST to sesUrl + "/send" with only a Content-Type header. No idempotency header or dedup exists, so a retry sends duplicate emails, including the LP enforcement notice. Handlers.event runs a plain INSERT INTO call_events with no ON CONFLICT or unique key shown, so a retry adds duplicate rows. The two UPDATE statements are keyed by call_id and lp_id and are repeatable on their own. They do not make the emails or the event insert safe. The email helper also swallows IOException and only logs it, so a failed send is not reported to the engine.

## Migration guidance

- Coverage: **ASSISTED**
- Suggested retry policy: maxAttempts=3
- **Suggested idempotency key: `callId:lpId`** — a target to implement, NOT a mechanism that exists. Apply it where this activity touches the outside world: an `Idempotency-Key` header on an outbound request, a keyed insert, or a lookup before a send. If you cannot, write why in `gaps.md` and name this key — never say the analysis supplied none.

## Variables

Read:

Written:

## External calls

- service-call: `Handlers.email` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:573 — side effect suspected

## Callee source — READ THESE TOO

The handler delegates its real work to the code below. Read each file, follow it
further if it delegates again (service → client → http), and translate that logic
into the activity body — do NOT stub a success return.

- `src/main/java/com/vishworks/assetmgmt/camunda/JobWorkers.java:85-91` — `com.vishworks.assetmgmt.camunda.Job.bind`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:157-166` — `com.vishworks.assetmgmt.handlers.Handlers.email`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:186-192` — `com.vishworks.assetmgmt.handlers.Handlers.m`
- `src/main/java/com/vishworks/assetmgmt/db/Db.java:171-175` — `com.vishworks.assetmgmt.db.Db.exec`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:91-98` — `com.vishworks.assetmgmt.handlers.Handlers.event`

## Source

Read the handler source with read_file: `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java` (source repo root).
Call graph: Job.bind, Handlers.email, Handlers.m, Db.exec, Handlers.event
