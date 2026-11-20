# Worker: issueCapitalCall

- Worker id (manifest `source.workerId`): `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#issueCapitalCall`
- Activity name: `IssueCapitalCall`
- Language/SDK: java/camunda-client
- Registration: src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:66 — `handle("issueCapitalCall", <handler>)`
- autoComplete: false (explicit completion)

## Classification

- Side-effect class: **non-idempotent** (confidence 0.5, provenance llm)
- Summary: The worker validates a capital call, splits the target amount across LPs by commitment, saves the call and its LP rows in one transaction, logs an event, and returns the follow-up wait schedule.
- Rationale: The main writes are keyed by callId. The insert into capital_calls uses ON CONFLICT (call_id) DO UPDATE and only resets process_instance_key. The insert into capital_call_lps uses ON CONFLICT (call_id, lp_id) DO NOTHING. Both run in one transaction with commit and rollback, so a retry does not create duplicate call or LP rows. Two weak points remain. First, Handlers.event does a plain INSERT into call_events with no deduplication. A retry adds a second audit row. Second, the deadline comes from LocalDateTime.now(). On a retry the stored deadline_date is not updated, but the returned deadlineAt is new, so it can differ from the stored value. I assume that a duplicate audit row and a shifted returned deadline are acceptable. The code does not verify this. [auto-downgraded from idempotent-with-key: the claimed key 'callId' is not backed by an in-code dedup mechanism the model could verify, so retries cannot be assumed safe]
- **Requires human confirmation** — treat the translation as a draft.
- **Unaccounted call receiver(s):** `HikariDataSource` — not in the analyzed repository and not a known-pure type, so the analysis could NOT prove this worker is side-effect free. Do not generate it as a plain retryable read; keep the call shape and let the review stop stand.

## Migration guidance

- Coverage: **ASSISTED**
- Suggested retry policy: maxAttempts=3

## Variables

Read:

Written:
- `lpList`: unknown
- `drawAmounts`: unknown
- `deadlineDays`: unknown
- `waitStage1`: unknown
- `waitStage2`: unknown
- `waitStage3`: unknown
- `waitFinal`: unknown
- `callDeadline`: unknown
- `deadlineAt`: unknown
- `bridgeTriggered`: unknown
- `bridgeResult`: unknown
- `highRiskLPs`: unknown
- `gpDecisions`: unknown
- `settlementMode`: unknown

## Exception → BPMN error mapping

- `JobException` (thrown in (handler body)) → error code ``

## External calls

- service-call: `Job.processInstanceKey` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:237 — side effect suspected

## Callee source — READ THESE TOO

The handler delegates its real work to the code below. Read each file, follow it
further if it delegates again (service → client → http), and translate that logic
into the activity body — do NOT stub a success return.

- `src/main/java/com/vishworks/assetmgmt/camunda/JobWorkers.java:85-91` — `com.vishworks.assetmgmt.camunda.Job.bind`
- `src/main/java/com/vishworks/assetmgmt/db/Db.java:113-115` — `com.vishworks.assetmgmt.db.Db.getConnection`
- `src/main/java/com/vishworks/assetmgmt/db/Db.java:171-175` — `com.vishworks.assetmgmt.db.Db.exec`
- `src/main/java/com/vishworks/assetmgmt/camunda/JobWorkers.java:80-82` — `com.vishworks.assetmgmt.camunda.Job.processInstanceKey`
- `src/main/java/com/vishworks/assetmgmt/util/Errors.java:22-33` — `com.vishworks.assetmgmt.util.Errors.message`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:91-98` — `com.vishworks.assetmgmt.handlers.Handlers.event`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:84-89` — `com.vishworks.assetmgmt.handlers.Handlers.isoSeconds`
- `src/main/java/com/vishworks/assetmgmt/util/Format.java:287-289` — `com.vishworks.assetmgmt.util.Format.rfc3339Utc`

## Source

Read the handler source with read_file: `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java` (source repo root).
Call graph: Job.bind, Db.getConnection, Db.exec, Job.processInstanceKey, Errors.message, Handlers.event, Handlers.isoSeconds, Format.rfc3339Utc
