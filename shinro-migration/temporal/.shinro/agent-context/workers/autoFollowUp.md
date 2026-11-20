# Worker: autoFollowUp

- Worker id (manifest `source.workerId`): `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#autoFollowUp`
- Activity name: `AutoFollowUp`
- Language/SDK: java/camunda-client
- Registration: src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:68 — `handle("autoFollowUp", <handler>)`
- autoComplete: false (explicit completion)

## Classification

- Side-effect class: **non-idempotent** (confidence 0.9, provenance llm)
- Summary: The worker sends a follow-up email to a limited partner about an unconfirmed capital call draw, marks the LP as overdue, and logs an audit event.
- Rationale: Handlers.email calls postJSON, a plain POST to sesUrl + "/send". It has no Idempotency-Key header and no deduplication, and the callId, lpId and stage fields are only in the body (rules 6 and 7). A retry can send a duplicate email. Handlers.event runs a plain INSERT into call_events with no ON CONFLICT clause, so a retry adds a duplicate audit row. The UPDATE on capital_call_lps is keyed by call_id and lp_id and is repeatable by itself, but it does not protect the email or the INSERT. Handlers.email also catches IOException and only logs it, so a failed send does not fail the job. A failed send is not retried, and the job still updates the status. The worker runs once per LP and once per escalation stage, so the key needs all three: call, LP and stage.

## Migration guidance

- Coverage: **ASSISTED**
- Suggested retry policy: maxAttempts=3
- **Suggested idempotency key: `callId:lpId:stage`** — a target to implement, NOT a mechanism that exists. Apply it where this activity touches the outside world: an `Idempotency-Key` header on an outbound request, a keyed insert, or a lookup before a send. If you cannot, write why in `gaps.md` and name this key — never say the analysis supplied none.

## Variables

Read:

Written:

## External calls

- service-call: `Handlers.email` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:322 — side effect suspected

## Callee source — READ THESE TOO

The handler delegates its real work to the code below. Read each file, follow it
further if it delegates again (service → client → http), and translate that logic
into the activity body — do NOT stub a success return.

- `src/main/java/com/vishworks/assetmgmt/camunda/JobWorkers.java:85-91` — `com.vishworks.assetmgmt.camunda.Job.bind`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:334-340` — `com.vishworks.assetmgmt.handlers.Handlers.atoi`
- `src/main/java/com/vishworks/assetmgmt/camunda/JobWorkers.java:94-98` — `com.vishworks.assetmgmt.camunda.Job.header`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:157-166` — `com.vishworks.assetmgmt.handlers.Handlers.email`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:186-192` — `com.vishworks.assetmgmt.handlers.Handlers.m`
- `src/main/java/com/vishworks/assetmgmt/db/Db.java:171-175` — `com.vishworks.assetmgmt.db.Db.exec`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:91-98` — `com.vishworks.assetmgmt.handlers.Handlers.event`

## Source

Read the handler source with read_file: `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java` (source repo root).
Call graph: Job.bind, Handlers.atoi, Job.header, Map.getOrDefault, Handlers.email, Handlers.m, Db.exec, Handlers.event
