# Gaps & manual work

## User tasks (become Temporal signals)

- `UserTask_GPApproval`

## Analysis gaps

- [major] dynamic-unresolved: a newWorker() registration takes its job type from a variable, so the job types it serves are not statically known; workers registered only this way are not analyzed
- [info] no-test-oracles: 22 test(s) across 8 file(s) were found, but none exercises a worker end to end, so the generated code has no recorded input/output to check against
- [major] unknown-dependency: "HikariDataSource" is called from worker code but is not defined in this repository, so its behaviour could not be determined from the source; the 2 worker(s) that reach it are escalated to the LLM for classification.
