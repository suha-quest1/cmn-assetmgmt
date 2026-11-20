# Migration notes

## Built
- Process `capital-call` is workflow `CapitalCallWorkflowImpl` on task queue `capital-call`.
- Process `lp-response` is workflow `LpResponseWorkflowImpl` on task queue `lp-response`.
- The capital-call workflow starts one lp-response child workflow for each LP.
- The 12 capital-call workers and 2 lp-response workers are activities.
- The Camunda messages are signals: `lpCommitment`, `gpDecision`, `forceSettlement`, `cancelCall`.
- The signal `lpCommitment` on capital-call goes to the child workflow of that LP.
- The GP approval user task is the signal `gpDecision`.
- The call deadline timer, the force settlement signal and the cancel signal race the child workflows.
- The workflow id replaces the Camunda process instance key.
- The report text names Temporal. The e-mail text names the Temporal UI.

## External systems
- The activities need a Postgres database, SES, the ML scorer and the credit facility.
- The settings come from the environment: DATABASE_URL, SES_URL, ML_SCORER_URL, CREDIT_FACILITY_URL, REPORT_DIR.
- The tests mock the activities. No test calls these systems.

## Idempotency
- Outbound HTTP calls carry an `Idempotency-Key` header. The key is in the worker md.
- The downstream service must dedupe on the key for retries to be safe.
- Database writes are upserts or absolute updates.
- Event inserts into `call_events` are not deduplicated. A retry can add a second event row.
