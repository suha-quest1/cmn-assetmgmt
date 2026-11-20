# Gaps

- `issueCapitalCall` has no idempotency key in the worker md. The activity upserts on callId. A human must confirm this is enough.
- `issueCapitalCall` and `settleAndReconcile` use a database connection that the analysis could not verify. A human must confirm the connection settings.
- `settleAndReconcile` has no idempotency key in the worker md. The activity rebuilds the ledger in one transaction. A human must confirm this is safe for retries.
- The GP approval user task has no timer in the BPMN. The workflow waits for the `gpDecision` signal with no limit. A human must decide if a timeout is needed.
- The runtime sample input needs Postgres, the ML scorer and the credit facility. Without them the run cannot finish.
- Call events are not deduplicated on retry.
