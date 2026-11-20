# Worker: scoreLPPortfolio

- Worker id (manifest `source.workerId`): `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#scoreLPPortfolio`
- Activity name: `ScoreLPPortfolio`
- Language/SDK: java/camunda-client
- Registration: src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:73 — `handle("scoreLPPortfolio", <handler>)`
- autoComplete: false (explicit completion)

## Classification

- Side-effect class: **non-idempotent** (confidence 0.8, provenance llm)
- Summary: The worker scores the risk and concentration of an LP portfolio, saves the HHI score to the capital call row, and logs an audit event.
- Rationale: The worker has two writes. The first is db.exec("UPDATE capital_calls SET concentration_hhi=$2 WHERE call_id=$1"). This UPDATE sets a fixed value for a given call_id, so a retry gives the same result. The second is Handlers.event, which runs a plain INSERT INTO call_events (call_id, lp_id, step, detail). The slice shows no ON CONFLICT clause, no unique constraint and no keyed lookup before the insert. A retry adds a duplicate audit row. The insert error is only logged and is not rethrown. The score computation is pure and deterministic. The duplicate audit row is a small effect, but it is a real change that the code does not deduplicate, so the class is non-idempotent. The suggested key is callId, because the worker runs once per capital call and reads no stage or round counter.

## Migration guidance

- Coverage: **ASSISTED**
- Suggested retry policy: maxAttempts=3
- **Suggested idempotency key: `callId`** — a target to implement, NOT a mechanism that exists. Apply it where this activity touches the outside world: an `Idempotency-Key` header on an outbound request, a keyed insert, or a lookup before a send. If you cannot, write why in `gaps.md` and name this key — never say the analysis supplied none.

## Variables

Read:

Written:

## Callee source — READ THESE TOO

The handler delegates its real work to the code below. Read each file, follow it
further if it delegates again (service → client → http), and translate that logic
into the activity body — do NOT stub a success return.

- `src/main/java/com/vishworks/assetmgmt/camunda/JobWorkers.java:85-91` — `com.vishworks.assetmgmt.camunda.Job.bind`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:435-437` — `com.vishworks.assetmgmt.handlers.Handlers.nz`
- `src/main/java/com/vishworks/assetmgmt/util/Format.java:31-37` — `com.vishworks.assetmgmt.util.Format.round`
- `src/main/java/com/vishworks/assetmgmt/db/Db.java:171-175` — `com.vishworks.assetmgmt.db.Db.exec`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:91-98` — `com.vishworks.assetmgmt.handlers.Handlers.event`
- `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:186-192` — `com.vishworks.assetmgmt.handlers.Handlers.m`

## Source

Read the handler source with read_file: `src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java` (source repo root).
Call graph: Job.bind, Handlers.nz, Format.round, Db.exec, Handlers.event, Handlers.m
