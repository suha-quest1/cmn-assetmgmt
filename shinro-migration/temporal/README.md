# Running the Temporal port locally

This stack runs the Temporal workflows in this directory against every
external dependency they need: a Temporal server + Web UI, the business
Postgres database, and the SES, credit-facility and ML-scorer mocks.
Workflows are started and signalled with the `temporal` CLI.

Every scenario below has been run against this stack; the "Result" lines are
real output. All scenarios use `secondsPerDay: 1`, which compresses the
multi-day timers (follow-ups at D+3/D+7/D+10, a 10-day response window) down
to seconds, so each one finishes in under a minute.

## Start the stack

```sh
cd shinro-migration/temporal
docker compose up -d --build
```

> This stack and the root Camunda `docker-compose.yml` share host ports
> (8081/8082/5001/5433), so only one can run at a time. Run
> `docker compose down` on the other stack first.

Wait for the `worker` container to log `MultiThreadedPoller` lines.

- Temporal Web UI: http://localhost:8233
- Business Postgres: `localhost:5433` (user/pass/db: `assetmgmt`)
- Mock SES log viewer: http://localhost:8081
- Mock credit facility: http://localhost:8082
- ML scorer: http://localhost:5001

Run all commands below from `shinro-migration/temporal/`. CLI commands go
through the `temporal-admin-tools` container:

```sh
docker compose exec temporal-admin-tools temporal workflow list --address temporal:7233
```

## Signal payloads

| Signal | Payload | Notes |
|---|---|---|
| `lpCommitment` | `{"lpId": "...", "responseStatus": "committed" \| "deferred", "amountUSD": 123.0}` | `responseStatus` defaults to `"committed"`. `amountUSD` is optional; if omitted, the LP's pro-rata draw amount is used. The first commitment per LP is the one recorded. |
| `gpDecision` | `{"lpId": "...", "action": "waive" \| "enforce", "gpName": "...", "comment": "..."}` | Values other than `"enforce"` are treated as `"waive"`. |
| `forceSettlement` | none | |
| `cancelCall` | none | |

The ML-scorer mock gives any LP whose id ends in `08` a risk score of `0.82`,
so you can trigger GP escalation on demand. Avoid that suffix when you don't
want escalation.

## Scenario 1 — Happy path

Every LP commits in full. No bridge, no GP escalation.

```sh
docker compose exec temporal-admin-tools temporal workflow start \
  --address temporal:7233 --task-queue capital-call --type capital-call --workflow-id CC-SIM-HAPPY \
  --input '{"callId":"CC-SIM-HAPPY","fundId":"FUND-1","targetAmountUSD":1000000,"deadlineDays":10,"secondsPerDay":5,
    "lpList":[{"lpId":"LP-01","commitmentUSD":600000,"email":"lp01@example.com"},
              {"lpId":"LP-02","commitmentUSD":400000,"email":"lp02@example.com"}]}'

# within ~2s of starting:
docker compose exec temporal-admin-tools temporal workflow signal --address temporal:7233 --workflow-id CC-SIM-HAPPY --name lpCommitment --input '{"lpId":"LP-01"}'
docker compose exec temporal-admin-tools temporal workflow signal --address temporal:7233 --workflow-id CC-SIM-HAPPY --name lpCommitment --input '{"lpId":"LP-02"}'

docker compose exec temporal-admin-tools temporal workflow describe --address temporal:7233 --workflow-id CC-SIM-HAPPY
```

**Result:** completed in ~1.5s. Both LPs `committed`, `gapPercent: 0`,
`bridgeTriggered: false`, `totalCommitted: 1000000`, `wiresSettled: 2`.

## Scenario 2 — Deferred commitment

One LP commits, one defers. The amounts put the gap at exactly 10%, which
does not trigger the bridge (the threshold is strictly greater than 10%).

```sh
docker compose exec temporal-admin-tools temporal workflow start \
  --address temporal:7233 --task-queue capital-call --type capital-call --workflow-id CC-SIM-DEFER \
  --input '{"callId":"CC-SIM-DEFER","fundId":"FUND-1","targetAmountUSD":1000000,"deadlineDays":10,"secondsPerDay":5,
    "lpList":[{"lpId":"LP-D1","commitmentUSD":900000,"email":"lpd1@example.com"},
              {"lpId":"LP-D2","commitmentUSD":100000,"email":"lpd2@example.com"}]}'

# within ~2s of starting:
docker compose exec temporal-admin-tools temporal workflow signal --address temporal:7233 --workflow-id CC-SIM-DEFER --name lpCommitment --input '{"lpId":"LP-D1"}'
docker compose exec temporal-admin-tools temporal workflow signal --address temporal:7233 --workflow-id CC-SIM-DEFER --name lpCommitment --input '{"lpId":"LP-D2","responseStatus":"deferred"}'

docker compose exec temporal-admin-tools temporal workflow describe --address temporal:7233 --workflow-id CC-SIM-DEFER
```

**Result:** completed in ~1.5s. LP-D2 `deferred` with `amountUSD: 0`,
`gapPercent: 10`, `bridgeTriggered: false`, `totalCommitted: 900000`.

## Scenario 3 — High-risk LP and GP decision

LP-08 scores 0.82, so it is escalated to the GP after committing. The
workflow waits for the `gpDecision` signal before settling.

```sh
docker compose exec temporal-admin-tools temporal workflow start \
  --address temporal:7233 --task-queue capital-call --type capital-call --workflow-id CC-SIM-GPGATE \
  --input '{"callId":"CC-SIM-GPGATE","fundId":"FUND-1","targetAmountUSD":1000000,"deadlineDays":10,"secondsPerDay":5,
    "lpList":[{"lpId":"LP-08","commitmentUSD":500000,"email":"lp08@example.com"},
              {"lpId":"LP-01","commitmentUSD":500000,"email":"lp01@example.com"}]}'

# within ~2s of starting:
docker compose exec temporal-admin-tools temporal workflow signal --address temporal:7233 --workflow-id CC-SIM-GPGATE --name lpCommitment --input '{"lpId":"LP-08"}'
docker compose exec temporal-admin-tools temporal workflow signal --address temporal:7233 --workflow-id CC-SIM-GPGATE --name lpCommitment --input '{"lpId":"LP-01"}'

# after ~5s the workflow is waiting for the GP (describe shows no Results section yet):
docker compose exec temporal-admin-tools temporal workflow describe --address temporal:7233 --workflow-id CC-SIM-GPGATE

# send the decision whenever you're ready:
docker compose exec temporal-admin-tools temporal workflow signal --address temporal:7233 --workflow-id CC-SIM-GPGATE --name gpDecision \
  --input '{"lpId":"LP-08","action":"enforce","gpName":"Jane GP","comment":"Confirmed via phone"}'

docker compose exec temporal-admin-tools temporal workflow describe --address temporal:7233 --workflow-id CC-SIM-GPGATE
```

**Result:** LP-08 scored `0.82` and was escalated. After the decision, the
call completed with
`"gpDecisions":[{"action":"enforce","gpName":"Jane GP","lpId":"LP-08","riskScore":0.82}]`.

## Scenario 4 — Cancel mid-flight

```sh
docker compose exec temporal-admin-tools temporal workflow start \
  --address temporal:7233 --task-queue capital-call --type capital-call --workflow-id CC-SIM-CANCEL \
  --input '{"callId":"CC-SIM-CANCEL","fundId":"FUND-1","targetAmountUSD":1000000,"deadlineDays":10,"secondsPerDay":5,
    "lpList":[{"lpId":"LP-C1","commitmentUSD":1000000,"email":"lpc1@example.com"}]}'

# within ~2s of starting, before any LP responds:
docker compose exec temporal-admin-tools temporal workflow signal --address temporal:7233 --workflow-id CC-SIM-CANCEL --name cancelCall

docker compose exec temporal-admin-tools temporal workflow describe --address temporal:7233 --workflow-id CC-SIM-CANCEL
```

**Result:** completed in ~1.2s with `"status":"cancelled"`.

## Scenario 5 — No response

No LP responds. Each LP receives all three follow-ups and is marked
defaulted; the 100% gap triggers the bridge.

```sh
docker compose exec temporal-admin-tools temporal workflow start \
  --address temporal:7233 --task-queue capital-call --type capital-call --workflow-id CC-SIM-NORESPONSE \
  --input '{"callId":"CC-SIM-NORESPONSE","fundId":"FUND-1","targetAmountUSD":1000000,"deadlineDays":10,"secondsPerDay":2,
    "lpList":[{"lpId":"LP-1","commitmentUSD":600000,"email":"lp1@example.com"},
              {"lpId":"LP-2","commitmentUSD":400000,"email":"lp2@example.com"}]}'

# send nothing; wait ~24s, then:
docker compose exec temporal-admin-tools temporal workflow describe --address temporal:7233 --workflow-id CC-SIM-NORESPONSE
```

**Result:** completed in ~11.5s. Both LPs `defaulted` with `followUps: 3`,
`gapPercent: 100`, `bridgeTriggered: true`, `totalCommitted: 0`.

## Inspecting results

```sh
docker compose exec postgres psql -U assetmgmt -d assetmgmt -c "select * from capital_calls;"
docker compose exec postgres psql -U assetmgmt -d assetmgmt -c "select * from capital_call_lps order by call_id, lp_id;"
```

- Temporal Web UI: http://localhost:8233 — search by workflow ID to see the full history
- Mock SES log: http://localhost:8081 — every notification sent
- Liquidity reports: `../../reports/<callId>.pdf` and `.json`

## Tear down

```sh
docker compose down -v   # -v also removes the Postgres volumes
```
