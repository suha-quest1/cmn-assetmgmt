# Worker: emitLiquidityReport

- Worker id (manifest `source.workerId`): `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#emitLiquidityReport`
- Activity name: `EmitLiquidityReport`
- Language/SDK: java/camunda-client
- Registration: src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:78 — `handle("emitLiquidityReport", <handler>)`
- autoComplete: false (explicit completion)

## Classification

- Side-effect class: **non-idempotent** (confidence 0.7, provenance llm)
- Summary: The worker writes a liquidity report as JSON and PDF files, marks the capital call as completed, and logs an audit event.
- Rationale: The file writes and the status UPDATE can repeat safely. The file paths come from in.callId, and Files.writeString and Files.write replace the files. The UPDATE sets fixed values by call_id. But Handlers.event runs a plain INSERT INTO call_events with no unique constraint or ON CONFLICT clause in the code slice. A retry adds a duplicate audit row. The report body also uses the current time (Handlers.nowUtc()) and the process instance key, so the output differs between attempts. No deduplication mechanism is visible, so the worker is non-idempotent.

## Migration guidance

- Coverage: **ASSISTED**
- Suggested retry policy: maxAttempts=3
- **Suggested idempotency key: `callId`** — a target to implement, NOT a mechanism that exists. Apply it where this activity touches the outside world: an `Idempotency-Key` header on an outbound request, a keyed insert, or a lookup before a send. If you cannot, write why in `gaps.md` and name this key — never say the analysis supplied none.

## Variables

Read:

Written:

## External calls

- service-call: `ReportWriter.writeReport` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:642 — side effect suspected
- service-call: `Job.processInstanceKey` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:642 — side effect suspected

## Callee source — READ THESE TOO

The handler delegates its real work to the code below. Read each file, follow it
further if it delegates again (service → client → http), and translate that logic
into the activity body — do NOT stub a success return.

- `src/main/java/com/vishworks/assetmgmt/camunda/JobWorkers.java:85-91` — `com.vishworks.assetmgmt.camunda.Job.bind`
- `src/main/java/com/vishworks/assetmgmt/handlers/ReportWriter.java:57-84` — `com.vishworks.assetmgmt.handlers.ReportWriter.writeReport`
- `src/main/java/com/vishworks/assetmgmt/camunda/JobWorkers.java:80-82` — `com.vishworks.assetmgmt.camunda.Job.processInstanceKey`
- `src/main/java/com/vishworks/assetmgmt/db/Db.java:171-175` — `com.vishworks.assetmgmt.db.Db.exec`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:91-98` — `com.vishworks.assetmgmt.handlers.Handlers.event`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:186-192` — `com.vishworks.assetmgmt.handlers.Handlers.m`

## Source

Read the handler source with read_file: `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java` (source repo root).
Call graph: Job.bind, ReportWriter.writeReport, Job.processInstanceKey, Db.exec, Handlers.event, Handlers.m
