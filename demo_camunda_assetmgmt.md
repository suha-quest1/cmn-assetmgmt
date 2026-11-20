# Demo guide: Intelligent Capital Call & Liquidity Orchestration on Camunda 8

This guide covers starting and stopping the local stack and gives a step-by-step demo script.

| | |
|---|---|
| **Use case** | `use_case.pdf`: Asset Management, Intelligent Capital Call & Liquidity Orchestration |
| **Orchestrator** | Camunda 8.10.9, the latest stable release, self-managed and open source. It runs as the lightweight "orchestration cluster" container: Zeebe engine, Operate, Tasklist and REST API, with H2 secondary storage. |
| **Implementation** | Java 21 (Maven). The job workers and API server use the official Camunda 8 Java client (`io.camunda:camunda-client-java` 8.10.9) over the Orchestration Cluster REST API (`/v2`). |
| **Ported from** | `../temporal-asset-management`, the Temporal version of the same use case |

---

## 1. What's in the box

```
camunda-asset-management/
├── bpmn/
│   ├── capital-call.bpmn      # parent process   (≈ Temporal CapitalCallWorkflow)
│   ├── lp-response.bpmn       # child process    (≈ Temporal LPResponseWorkflow)
│   └── gp-approval.form       # Tasklist form for the human GP approval gate
├── pom.xml                    # Maven build: one executable jar for every service
├── src/main/java/com/vishworks/assetmgmt/
│   ├── Main.java              # entry point: java -jar … worker|apiserver|mockses|mockcreditfacility|demodriver
│   ├── cmd/WorkerMain         # job workers (all service tasks) + auto-deploys bpmn/
│   ├── cmd/ApiServerMain      # REST/webhook endpoints + live dashboard (:8090)
│   ├── cmd/DemoDriverMain     # scripted 10-LP demo
│   ├── cmd/MockSesMain        # email / Slack log viewer (:8081)
│   ├── cmd/MockCreditFacilityMain # bridge credit line stub (:8082)
│   ├── camunda/               # Camunda Java client setup + job worker registration (retries, back-off)
│   ├── handlers/              # business logic of every BPMN service task + liquidity report
│   ├── db/  http/  json/  models/  report/  util/
├── src/main/resources/static/dashboard.html   # the dashboard (unchanged)
├── services/ml-scorer/        # Flask default-risk scoring model (:5001)
├── db/                        # Postgres fund ledger schema + 10 seeded LPs
├── camunda/application.yaml   # Camunda 8.10 lightweight config (H2, basic auth demo/demo)
├── tools/gen_bpmn.py          # generator for the two BPMN files (incl. diagram layout)
├── docker-compose.yml
└── Makefile
```

### Mapping from the use-case document to Camunda

| Use-case requirement | Camunda 8 implementation |
|---|---|
| `CapitalCallWorkflow` (parent) | BPMN process `capital-call` |
| `LPResponseWorkflow` (child, one per LP) | BPMN process `lp-response`, started by a **parallel multi-instance call activity** over `lpList` |
| 1. `issueCapitalCall` | Service task, job type `issueCapitalCall`. Inserts the call into Postgres and computes the pro-rata draw for each LP. |
| 2. `notifyLPs` (SES fan-out) | Service task `notifyLPs`, which sends to mock SES |
| 3./4. Start children concurrently, `Promise.all` with a deadline timer | The multi-instance call activity collects `lpResponses`. An interrupting **boundary timer** (`=callDeadline`) acts as the hard deadline. |
| `waitForSignal("lpCommitment")` | **Message catch events** `lpCommitment` with correlation key `callId:lpId`, behind **event-based gateways** |
| `autoFollowUp` (email D+3, call D+7, legal notice D+10) | Timer events (D+3, D+7, D+10) racing the message, each followed by an `autoFollowUp` task (header `stage` = 1, 2 or 3) |
| Child returns committed / defaulted / deferred | `recordLPResponse` task (header `status`). Its output `lpResponse` goes into the multi-instance output collection. |
| 5. `aggregateLiquidity` | Service task: committed vs target, gap in USD and % |
| 6. `predictDefaultRisk` | Service task calling the Flask ML scorer; builds `highRiskLPs` |
| `scoreLPPortfolio` | Service task: HHI concentration, top risky LPs, expected loss |
| 7. Branch on gap > 10% → `triggerBridge` | Parallel split, then the exclusive gateway `=gapPercent > 10`, then `triggerBridge` (mock credit facility) |
| 8. Risk > 0.7 → `escalateToGP` → `gpApprovalGate` (human) | Parallel multi-instance **sub-process** per high-risk LP: `escalateToGP` (Slack-style alert), then a **Camunda user task** in Tasklist with a form. An interrupting message boundary `gpDecision` lets the API answer instead (`POST /gp-decision`). Then `applyGPDecision`. |
| 9. After all branches resolve → `settleAndReconcile` | Parallel join, then wire stubs and double-entry ledger (idempotent on retry) |
| 10. `emitLiquidityReport` (PDF + JSON) | Writes `reports/{callId}.pdf` and `reports/{callId}.json` |
| `POST /api/capital-calls/{callId}/lp-response` | Publishes message `lpCommitment` (buffered for 1 h, idempotent via `messageId`) |
| `POST /api/capital-calls/{callId}/gp-decision` | Correlates message `gpDecision`. Returns 409 if no gate is open. |
| Extras carried over from the Temporal repo: `forceSettlement`, `cancelCall` signals | Interrupting **message boundary events** on the multi-instance. Force → `finalizeResponses` → continue. Cancel → `markCallCancelled` → end. |
| Retries, failures | Each service task has `retries="3"`. Failed jobs back off for 5 s; once retries run out, an **incident** shows up in Operate and can be retried from there. |

---

## 2. Prerequisites

* Docker Desktop running, with at least 4 GB of memory for Docker. Camunda uses about 1–1.5 GB.
* JDK 21 and Maven 3.9+ on the host, used only by `make demo`, `make build` and `make test`. Without them, use `docker compose --profile demo run --rm demo-driver`.
* Free ports: **8080** (Camunda), 26500, 9600, **8090** (API/dashboard), 8081, 8082, 5001, 5433 (Postgres).

> ⚠️ The Temporal version of this demo also uses port 8080 (Temporal UI). Stop it first: `cd ../temporal-asset-management && docker compose down`.

---

## 3. Start / stop Camunda and the demo stack

All commands run from `camunda-asset-management/`.

### Start

```bash
make up            # = docker compose up -d --build, then waits until Camunda is healthy
```

The first start pulls `camunda/camunda:8.10.9` (about 1 GB) and builds the Java images (Maven downloads the dependencies on the first build), which takes a few minutes. Later starts take about 30–40 s. At startup the worker deploys `capital-call.bpmn`, `lp-response.bpmn` and `gp-approval.form` automatically.

Check the stack:

```bash
make status                                  # all containers Up, orchestration "healthy"
curl -s localhost:8080/v2/topology | jq .     # broker 8.10.9, partition healthy
docker compose logs worker | grep deployed    # deployment confirmation
```

### URLs

| What | URL | Login |
|---|---|---|
| **Operate**: process monitoring, instances, incidents | http://localhost:8080/operate | demo / demo |
| **Tasklist**: GP approval gate (human task) | http://localhost:8080/tasklist | demo / demo |
| Camunda Admin (users, roles) | http://localhost:8080/admin | demo / demo |
| Camunda REST API (Swagger UI) | http://localhost:8080/swagger-ui/index.html | (API unprotected locally) |
| **Capital Call Control Room**: dashboard and LP portal simulator | http://localhost:8090 | — |
| **Email / Slack log** (mock SES) | http://localhost:8081 | — |
| Generated reports | http://localhost:8090/reports/{callId}.pdf | — |

### Stop

```bash
make down          # stop containers, keep all data (process history, ledger)
make clean         # stop and wipe the Camunda + Postgres volumes and generated reports (fresh demo)
```

Use `make logs` to follow the worker and API server logs. Run `make up` again to restart.

---

## 4. Demo walkthrough (about 15 minutes)

The demo compresses time: **5 seconds = 1 business day**. The D+3, D+7 and D+10 follow-ups therefore fire at about 15 s, 35 s and 50 s. A whole capital call takes about one minute plus the time you spend in Tasklist.

The seeded LPs behave as follows:

| LP | Behaviour | What it shows |
|---|---|---|
| lp-01 … lp-07 | Commit within seconds | Happy path; the multi-instance progresses live |
| lp-08 | Commits, but the ML model scores it **0.82** | GP escalation, then the **human approval gate in Tasklist** |
| lp-09 | Misses D+3, gets the email follow-up, then commits late | Timer vs message race (event-based gateway) |
| lp-10 | Never responds | Email D+3 → call D+7 → legal notice D+10 → **defaulted**. That leaves an 11.49% gap, which is over 10%, so the **bridge drawdown** runs. |

### Step 0: Show the models (2 min)

1. Open **Operate** → *Processes*. There are two definitions: **Intelligent Capital Call & Liquidity Orchestration** (`capital-call`) and **LP Response (per LP)** (`lp-response`).
2. Walk through `capital-call` from left to right:
   - Issue, then notify.
   - The multi-instance call activity (≡ one child workflow per LP) with its three boundary events: hard deadline, GP force-settlement and GP cancel.
   - Aggregate, then ML risk score, then portfolio risk.
   - A parallel split: the bridge branch (gap > 10%) and the GP-review branch (risk > 0.7) run concurrently.
   - A parallel join, then settle, then the report.
3. Open `lp-response` and point out the event-based gateways. At each stage an *LP commitment* message races a timer; when the timer wins, a tiered follow-up goes out.

> Message: the Temporal code becomes a *visual, executable* model. Business users, compliance and auditors read the same artifact the engine runs.

### Step 1: Start the capital call (1 min)

In a terminal:

```bash
make demo          # java -jar target/camunda-assetmgmt.jar demodriver   (the GP decision is left for you in Tasklist)
```

You could also click **Issue capital call to all 10 LPs** in the dashboard (http://localhost:8090) and then press *Commit* per LP yourself, acting as the LP portal.

The driver prints the `callId` (e.g. `CC-2026-4119`), the Operate link and the dashboard link. Open all three windows side by side:

* **Dashboard** (`http://localhost:8090/?call=<callId>`)
* **Operate**: click the instance link printed by the driver
* **Email log** (http://localhost:8081)

### Step 2: LP responses in real time (1 min)

* **Operate**: the call activity shows 10 active child instances; the counter on the element drops as LPs commit. Click the drill-down icon on the call activity to open a child `lp-response` instance and see the token waiting at the event-based gateway.
* **Dashboard**: LP statuses switch from `notified` to `committed`, and the committed amount climbs.
* **Email log**: 10 "Capital Call … Action Required" notifications (`notifyLPs`).

### Step 3: Autonomous follow-ups (1 min)

At about 15 s (D+3), **lp-09** and **lp-10** get the *email reminder*. lp-09 then commits (late), and its child instance completes via the second message catch.

lp-10 keeps escalating: a *phone call* at D+7 (~35 s), then a *legal notice* at D+10 (~50 s), and then it is defaulted. Show this in the email log (orange `followup` rows) and in the lp-10 child instance in Operate, which follows the timer path all the way to **LP defaulted**.

### Step 4: Liquidity, ML risk and bridge financing (1 min)

Once all 10 children complete, the parent continues:

* `aggregateLiquidity`: $44.25M of $50M is committed, a **gap of 11.49%**.
* `predictDefaultRisk`: the Flask model scores every LP. lp-08 scores **0.82** (red in the dashboard).
* The parallel gateway forks.
  * **Bridge branch**: `=gapPercent > 10` is true, so `triggerBridge` draws $5.75M on the credit line (1.5% fee). The dashboard KPI shows the confirmation.
  * **GP branch**: lp-08 is a committed LP above 0.7, so `escalateToGP` posts a Slack-style alert (red `escalation` row in the email log). A **user task** "GP approval gate" is now open, and the dashboard status shows `awaiting_gp`.

In Operate, the token sits on the user task while the bridge branch has already finished. The parallel join waits for both.

### Step 5: Human in the loop, the GP approval gate (2 min)

1. Open **Tasklist** (http://localhost:8080/tasklist, `demo`/`demo`).
2. Open the task **"GP approval gate: waive or enforce"**. The form shows the LP, its risk score, commitment and draw amount.
3. Click **Assign to me**, choose **Enforce** (or Waive), enter a rationale, then **Complete task**.

You can also make the decision headlessly, as a Slack button or the investor-portal backend would:

```bash
curl -X POST localhost:8090/api/capital-calls/<callId>/gp-decision \
  -H 'Content-Type: application/json' \
  -d '{"lpId":"lp-08","action":"waive","gpName":"Jane Doe"}'
```

This correlates the `gpDecision` message to the boundary event on the user task, which closes the task. Whichever channel answers first wins; nothing gets decided twice.

With **Enforce**, the email log shows a red `enforcement_warning` to lp-08 plus an audit message to the GP channel. The LP's commitment stays in the totals, matching the original implementation.

### Step 6: Settlement and regulatory report (1 min)

* The parallel join completes, then `settleAndReconcile`. The dashboard's **settlement ledger** shows 9 LP wires (`fund/cash` ↔ `lp/<id>/capital`), the bridge drawdown (`fund/cash` ↔ `liability/credit-facility`) and the financing fee.
* `emitLiquidityReport`: click **PDF report** or **JSON** in the dashboard, or open `reports/<callId>.pdf`. The report covers the liquidity summary, LP table with risk scores and follow-ups, portfolio risk, GP decisions and the double-entry ledger.
* **Operate**: the instance is *Completed*. Open the **instance history** (left panel) for the full audit trail of every step, and the **Variables** panel to see `lpResponses`, `gapPercent`, `bridgeResult`, `gpDecisions` and so on.

The driver ends with: `■ done in 57s. Report: http://localhost:8090/reports/<callId>.pdf`

### Step 7 (optional): Exception paths

**GP forces settlement early.** Start a slow call, let a few LPs commit, then close the window:

```bash
curl -s -XPOST localhost:8090/api/capital-calls -H 'Content-Type: application/json' \
     -d '{"callId":"CC-DEMO-FORCE","secondsPerDay":30}'
curl -s -XPOST localhost:8090/api/capital-calls/CC-DEMO-FORCE/lp-response -H 'Content-Type: application/json' -d '{"lpId":"lp-01"}'
curl -s -XPOST localhost:8090/api/capital-calls/CC-DEMO-FORCE/lp-response -H 'Content-Type: application/json' -d '{"lpId":"lp-02","status":"deferred"}'
curl -s -XPOST localhost:8090/api/capital-calls/CC-DEMO-FORCE/force-settlement
```

In Operate, the interrupting message boundary cancels the remaining children. *Close response window* then marks the outstanding LPs as defaulted, and the flow continues. With a gap of about 75%, the bridge runs for the rest. The dashboard has the same actions as the **Force settlement** and **Cancel call** buttons.

**GP cancels the call.** Use `POST /api/capital-calls/<callId>/cancel-call`. The flow goes to *Mark call cancelled*, LPs get cancellation emails, and the process ends at **Capital call cancelled**.

**Incident and retry.** Stop the ML scorer, then run a call:

```bash
docker compose stop ml-scorer
make demo-auto
```

`predictDefaultRisk` fails 3 times, and Operate shows a **red incident** on the element with the error message. Then:

```bash
docker compose start ml-scorer
```

In Operate, click **Retry** on the incident. The instance resumes from exactly that step; nothing upstream is re-executed.

**Version migration and changing a rule live.** Change the gap threshold in the model, for example edit `=gapPercent > 10` in `tools/gen_bpmn.py`, then run `make bpmn deploy`. New calls use version 2; Operate shows both versions side by side.

---

## 5. API reference (port 8090)

| Method & path | Body | Camunda action |
|---|---|---|
| `POST /api/capital-calls` | `{callId?, fundId?, targetAmountUSD?, deadlineDays?, secondsPerDay?, lpIds?[]}` | Create a `capital-call` instance (defaults: all 10 LPs, $50M, 10 days, real time) |
| `POST /api/capital-calls/{callId}/lp-response` | `{lpId, amount?, status?: "deferred"}` | Publish message `lpCommitment`, key `callId:lpId` |
| `POST /api/capital-calls/{callId}/gp-decision` | `{lpId, action: "waive"\|"enforce", gpName, comment?}` | Correlate message `gpDecision`, key `callId:lpId` |
| `POST /api/capital-calls/{callId}/force-settlement` | – | Correlate message `forceSettlement`, key `callId` |
| `POST /api/capital-calls/{callId}/cancel-call` | – | Correlate message `cancelCall`, key `callId` |
| `GET /api/capital-calls` / `GET /api/capital-calls/{callId}` | – | Read model from the Postgres fund ledger |
| `GET /api/lps` | – | Seeded LPs |

You can also drive Camunda directly, e.g. `curl -XPOST localhost:8080/v2/process-instances -H 'Content-Type: application/json' -d '{"processDefinitionId":"capital-call","variables":{...}}'`.

Inspect the ledger DB with `docker compose exec postgres psql -U assetmgmt -c 'select * from ledger_entries'`.

---

## 6. Troubleshooting

| Symptom | Fix |
|---|---|
| `orchestration` never becomes healthy | Give Docker more memory (≥ 4 GB). Check `docker compose logs orchestration`. |
| Port 8080 already in use | Stop the Temporal stack or any other service on 8080. |
| Worker logs `waiting for Camunda …`, or Camunda client warnings about failed job activation (connection refused) | Camunda is still starting or restarting; the worker retries automatically. |
| Driver says "capital call never appeared" | `docker compose logs worker`. Look for an incident on *Issue capital call* in Operate. |
| GP decision returns 409 | The gate isn't open yet (it opens after the deadline and risk scoring) or has already been decided. |
| Want a clean slate | `make clean && make up` |
