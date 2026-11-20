# Process: capital-call — Intelligent Capital Call & Liquidity Orchestration

- File: `bpmn/capital-call.bpmn`
- Executable: yes
- Description: BPMN process "Intelligent Capital Call & Liquidity Orchestration" (id: capital-call) — executable.
Contains 33 flow node(s) and 33 sequence flow(s): 4 boundaryEvent, 1 callActivity, 3 endEvent, 6 exclusiveGateway, 2 parallelGateway, 13 serviceTask, 2 startEvent, 1 subProcess, 1 userTask.
Service job types: aggregateLiquidity, applyGPDecision, emitLiquidityReport, escalateToGP, finalizeResponses, issueCapitalCall, markCallCancelled, notifyLPs, predictDefaultRisk, scoreLPPortfolio, settleAndReconcile, triggerBridge.
Correlates message(s): Message_CancelCall, Message_ForceSettlement, Message_GPDecision.

Flow:
  Start: none event "GP issues capital call"
  Service task "Issue capital call" (job type "issueCapitalCall")
  Service task "Notify LPs (SES batch)" (job type "notifyLPs")
  Call activity "Collect LP responses (one LP Response process per LP)" → process "lp-response"
  Exclusive (XOR) gateway "Gateway_Responses"
    ├─[→]
      Service task "Aggregate liquidity" (job type "aggregateLiquidity")
      Service task "Predict LP default risk (ML)" (job type "predictDefaultRisk")
      Service task "Score LP portfolio (concentration)" (job type "scoreLPPortfolio")
      Parallel (AND) gateway "Gateway_Split"
        ├─[→]
          Exclusive (XOR) gateway "Liquidity gap > 10%?"
            ├─[yes]
              Service task "Trigger bridge (credit line drawdown)" (job type "triggerBridge")
              Exclusive (XOR) gateway "Gateway_GapMerge"
                ├─[→]
                  Parallel (AND) gateway "Gateway_Join"
                    ├─[→]
                      Service task "Settle & reconcile (wires + ledger)" (job type "settleAndReconcile")
                      Service task "Emit liquidity report (PDF + JSON)" (job type "emitLiquidityReport")
                      End: none event "Capital call settled & reported"
            ├─[no]
              → Gateway_GapMerge (already described)
        ├─[→]
          Exclusive (XOR) gateway "High-risk LPs (score > 0.7)?"
            ├─[yes]
              Sub-process "GP review per high-risk LP"
              Exclusive (XOR) gateway "Gateway_HighRiskMerge"
                ├─[→]
                  → Gateway_Join (already described)
            ├─[no]
              → Gateway_HighRiskMerge (already described)
  Start: none event "Start_GP"
  Service task "Escalate to GP (Slack alert)" (job type "escalateToGP")
  User task "GP approval gate: waive or enforce" (groups gp-investment-committee)
  Exclusive (XOR) gateway "Gateway_Decision"
    ├─[→]
      Service task "Apply GP decision (enforcement notice if enforce)" (job type "applyGPDecision")
      End: none event "End_GP"

Boundary events (interrupts / error handling):
- timer event "Hard deadline" (timeDuration==callDeadline) on "Collect LP responses (one LP Response process per LP)" routes to Close response window (deadline)
- message event "GP forces settlement" (message "Message_ForceSettlement") on "Collect LP responses (one LP Response process per LP)" routes to Close response window (default outstanding LPs)
- message event "GP cancels call" (message "Message_CancelCall") on "Collect LP responses (one LP Response process per LP)" routes to Mark call cancelled
- message event "GP decision via API" (message "Message_GPDecision") on "GP approval gate: waive or enforce" routes to Gateway_Decision
- Messages: Message_CancelCall, Message_ForceSettlement, Message_GPDecision

## Elements

- `Start_Call` **startEvent** "GP issues capital call" classificationKey=`startEvent.none`
- `Task_Issue` **serviceTask** "Issue capital call" jobType=`issueCapitalCall` classificationKey=`serviceTask`
- `Task_Notify` **serviceTask** "Notify LPs (SES batch)" jobType=`notifyLPs` classificationKey=`serviceTask`
- `Call_LPResponses` **callActivity** "Collect LP responses (one LP Response process per LP)" calls `lp-response` classificationKey=`callActivity`
  - in: `=null` → `lpResponse`
  - out: `lpResponse` ← `=lpResponse`
- `Boundary_Deadline` **boundaryEvent** "Hard deadline" event=timer timer=`timeDuration==callDeadline` attachedTo=`Call_LPResponses`
- `Boundary_Force` **boundaryEvent** "GP forces settlement" event=message:Message_ForceSettlement attachedTo=`Call_LPResponses`
- `Boundary_Cancel` **boundaryEvent** "GP cancels call" event=message:Message_CancelCall attachedTo=`Call_LPResponses`
- `Task_Finalize` **serviceTask** "Close response window (default outstanding LPs)" jobType=`finalizeResponses` classificationKey=`serviceTask`
- `Task_FinalizeDeadline` **serviceTask** "Close response window (deadline)" jobType=`finalizeResponses` classificationKey=`serviceTask`
- `Task_Cancel` **serviceTask** "Mark call cancelled" jobType=`markCallCancelled` classificationKey=`serviceTask`
- `End_Cancelled` **endEvent** "Capital call cancelled" classificationKey=`endEvent.none`
- `Gateway_Responses` **exclusiveGateway**
- `Task_Aggregate` **serviceTask** "Aggregate liquidity" jobType=`aggregateLiquidity` classificationKey=`serviceTask`
- `Task_Predict` **serviceTask** "Predict LP default risk (ML)" jobType=`predictDefaultRisk` classificationKey=`serviceTask`
- `Task_Portfolio` **serviceTask** "Score LP portfolio (concentration)" jobType=`scoreLPPortfolio` classificationKey=`serviceTask`
- `Gateway_Split` **parallelGateway**
- `Gateway_Gap` **exclusiveGateway** "Liquidity gap > 10%?"
- `Task_Bridge` **serviceTask** "Trigger bridge (credit line drawdown)" jobType=`triggerBridge` classificationKey=`serviceTask`
- `Gateway_GapMerge` **exclusiveGateway**
- `Gateway_HighRisk` **exclusiveGateway** "High-risk LPs (score > 0.7)?"
- `Sub_GPReview` **subProcess** "GP review per high-risk LP"
  - in: `=null` → `gpAction`
  - in: `=null` → `gpName`
  - in: `=null` → `gpComment`
- `Start_GP` **startEvent** classificationKey=`startEvent.none`
- `Task_Escalate` **serviceTask** "Escalate to GP (Slack alert)" jobType=`escalateToGP` classificationKey=`serviceTask`
- `UserTask_GPApproval` **userTask** "GP approval gate: waive or enforce" formId=`gp-approval-form` classificationKey=`userTask`
- `Boundary_GPDecision` **boundaryEvent** "GP decision via API" event=message:Message_GPDecision attachedTo=`UserTask_GPApproval`
- `Gateway_Decision` **exclusiveGateway**
- `Task_ApplyDecision` **serviceTask** "Apply GP decision (enforcement notice if enforce)" jobType=`applyGPDecision` classificationKey=`serviceTask`
- `End_GP` **endEvent** classificationKey=`endEvent.none`
- `Gateway_HighRiskMerge` **exclusiveGateway**
- `Gateway_Join` **parallelGateway**
- `Task_Settle` **serviceTask** "Settle & reconcile (wires + ledger)" jobType=`settleAndReconcile` classificationKey=`serviceTask`
- `Task_Report` **serviceTask** "Emit liquidity report (PDF + JSON)" jobType=`emitLiquidityReport` classificationKey=`serviceTask`
- `End_Call` **endEvent** "Capital call settled & reported" classificationKey=`endEvent.none`

## Sequence flows

- `Start_GP` → `Task_Escalate`
- `Task_Escalate` → `UserTask_GPApproval`
- `UserTask_GPApproval` → `Gateway_Decision`
- `Boundary_GPDecision` → `Gateway_Decision`
- `Gateway_Decision` → `Task_ApplyDecision`
- `Task_ApplyDecision` → `End_GP`
- `Start_Call` → `Task_Issue`
- `Task_Issue` → `Task_Notify`
- `Task_Notify` → `Call_LPResponses`
- `Boundary_Deadline` → `Task_FinalizeDeadline`
- `Boundary_Force` → `Task_Finalize`
- `Boundary_Cancel` → `Task_Cancel`
- `Task_Cancel` → `End_Cancelled`
- `Call_LPResponses` → `Gateway_Responses`
- `Task_Finalize` → `Gateway_Responses`
- `Task_FinalizeDeadline` → `Gateway_Responses`
- `Gateway_Responses` → `Task_Aggregate`
- `Task_Aggregate` → `Task_Predict`
- `Task_Predict` → `Task_Portfolio`
- `Task_Portfolio` → `Gateway_Split`
- `Gateway_Split` → `Gateway_Gap`
- `Gateway_Gap` → `Task_Bridge` ("yes") — condition: `=gapPercent > 10`
- `Task_Bridge` → `Gateway_GapMerge`
- `Gateway_Gap` → `Gateway_GapMerge` ("no") — default
- `Gateway_Split` → `Gateway_HighRisk`
- `Gateway_HighRisk` → `Sub_GPReview` ("yes") — condition: `=count(highRiskLPs) > 0`
- `Sub_GPReview` → `Gateway_HighRiskMerge`
- `Gateway_HighRisk` → `Gateway_HighRiskMerge` ("no") — default
- `Gateway_GapMerge` → `Gateway_Join`
- `Gateway_HighRiskMerge` → `Gateway_Join`
- `Gateway_Join` → `Task_Settle`
- `Task_Settle` → `Task_Report`
- `Task_Report` → `End_Call`

## Job types → workers

- `aggregateLiquidity` → worker `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#aggregateLiquidity` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:71 — see `workers/aggregateLiquidity.md`
- `applyGPDecision` → worker `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#applyGPDecision` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:76 — see `workers/applyGPDecision.md`
- `emitLiquidityReport` → worker `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#emitLiquidityReport` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:78 — see `workers/emitLiquidityReport.md`
- `escalateToGP` → worker `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#escalateToGP` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:75 — see `workers/escalateToGP.md`
- `finalizeResponses` → worker `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#finalizeResponses` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:70 — see `workers/finalizeResponses.md`
- `issueCapitalCall` → worker `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#issueCapitalCall` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:66 — see `workers/issueCapitalCall.md`
- `markCallCancelled` → worker `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#markCallCancelled` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:79 — see `workers/markCallCancelled.md`
- `notifyLPs` → worker `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#notifyLPs` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:67 — see `workers/notifyLPs.md`
- `predictDefaultRisk` → worker `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#predictDefaultRisk` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:72 — see `workers/predictDefaultRisk.md`
- `scoreLPPortfolio` → worker `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#scoreLPPortfolio` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:73 — see `workers/scoreLPPortfolio.md`
- `settleAndReconcile` → worker `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#settleAndReconcile` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:77 — see `workers/settleAndReconcile.md`
- `triggerBridge` → worker `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#triggerBridge` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:74 — see `workers/triggerBridge.md`
