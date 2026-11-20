# Process: lp-response — LP Response (per LP)

- File: `bpmn/lp-response.bpmn`
- Executable: yes
- Description: BPMN process "LP Response (per LP)" (id: lp-response) — executable.
Contains 21 flow node(s) and 23 sequence flow(s): 2 endEvent, 4 eventBasedGateway, 1 exclusiveGateway, 8 intermediateCatchEvent, 5 serviceTask, 1 startEvent.
Service job types: autoFollowUp, recordLPResponse.
Correlates message(s): Message_LPCommitment.

Flow:
  Start: none event "LP notified"
  Event-based gateway "Gateway_Await1"
    ├─[→]
      Catch message event "LP commitment received" (message "Message_LPCommitment")
      Exclusive (XOR) gateway "Gateway_CommitMerge"
        ├─[→]
          Service task "Record LP commitment" (job type "recordLPResponse")
          End: none event "LP committed"
    ├─[→]
      Catch timer event "D+3 elapsed" (timeDuration==waitStage1)
      Service task "Auto follow-up: Email reminder (D+3)" (job type "autoFollowUp")
      Event-based gateway "Gateway_Await2"
        ├─[→]
          Catch message event "LP commitment received" (message "Message_LPCommitment")
          → Gateway_CommitMerge (already described)
        ├─[→]
          Catch timer event "D+7 elapsed" (timeDuration==waitStage2)
          Service task "Auto follow-up: Phone call (D+7)" (job type "autoFollowUp")
          Event-based gateway "Gateway_Await3"
            ├─[→]
              Catch message event "LP commitment received" (message "Message_LPCommitment")
              → Gateway_CommitMerge (already described)
            ├─[→]
              Catch timer event "D+10 elapsed" (timeDuration==waitStage3)
              Service task "Auto follow-up: Legal notice (D+10)" (job type "autoFollowUp")
              Event-based gateway "Gateway_Await4"
                ├─[→]
                  Catch message event "LP commitment received" (message "Message_LPCommitment")
                  → Gateway_CommitMerge (already described)
                ├─[→]
                  Catch timer event "Call deadline reached" (timeDuration==waitFinal)
                  Service task "Record LP default" (job type "recordLPResponse")
                  End: none event "LP defaulted"
- Messages: Message_LPCommitment

## Elements

- `Start_LP` **startEvent** "LP notified" classificationKey=`startEvent.none`
- `Gateway_Await1` **eventBasedGateway**
- `Msg_Commit1` **intermediateCatchEvent** "LP commitment received" event=message:Message_LPCommitment
- `Timer_Stage1` **intermediateCatchEvent** "D+3 elapsed" event=timer timer=`timeDuration==waitStage1`
- `Task_FollowUp1` **serviceTask** "Auto follow-up: Email reminder (D+3)" jobType=`autoFollowUp` classificationKey=`serviceTask`
- `Gateway_Await2` **eventBasedGateway**
- `Msg_Commit2` **intermediateCatchEvent** "LP commitment received" event=message:Message_LPCommitment
- `Timer_Stage2` **intermediateCatchEvent** "D+7 elapsed" event=timer timer=`timeDuration==waitStage2`
- `Task_FollowUp2` **serviceTask** "Auto follow-up: Phone call (D+7)" jobType=`autoFollowUp` classificationKey=`serviceTask`
- `Gateway_Await3` **eventBasedGateway**
- `Msg_Commit3` **intermediateCatchEvent** "LP commitment received" event=message:Message_LPCommitment
- `Timer_Stage3` **intermediateCatchEvent** "D+10 elapsed" event=timer timer=`timeDuration==waitStage3`
- `Task_FollowUp3` **serviceTask** "Auto follow-up: Legal notice (D+10)" jobType=`autoFollowUp` classificationKey=`serviceTask`
- `Gateway_Await4` **eventBasedGateway**
- `Msg_Commit4` **intermediateCatchEvent** "LP commitment received" event=message:Message_LPCommitment
- `Timer_Deadline` **intermediateCatchEvent** "Call deadline reached" event=timer timer=`timeDuration==waitFinal`
- `Task_RecordDefault` **serviceTask** "Record LP default" jobType=`recordLPResponse` classificationKey=`serviceTask`
- `End_Defaulted` **endEvent** "LP defaulted" classificationKey=`endEvent.none`
- `Gateway_CommitMerge` **exclusiveGateway**
- `Task_RecordCommit` **serviceTask** "Record LP commitment" jobType=`recordLPResponse` classificationKey=`serviceTask`
- `End_Committed` **endEvent** "LP committed" classificationKey=`endEvent.none`

## Sequence flows

- `Start_LP` → `Gateway_Await1`
- `Gateway_Await1` → `Msg_Commit1`
- `Gateway_Await1` → `Timer_Stage1`
- `Timer_Stage1` → `Task_FollowUp1`
- `Task_FollowUp1` → `Gateway_Await2`
- `Gateway_Await2` → `Msg_Commit2`
- `Gateway_Await2` → `Timer_Stage2`
- `Timer_Stage2` → `Task_FollowUp2`
- `Task_FollowUp2` → `Gateway_Await3`
- `Gateway_Await3` → `Msg_Commit3`
- `Gateway_Await3` → `Timer_Stage3`
- `Timer_Stage3` → `Task_FollowUp3`
- `Task_FollowUp3` → `Gateway_Await4`
- `Gateway_Await4` → `Msg_Commit4`
- `Gateway_Await4` → `Timer_Deadline`
- `Timer_Deadline` → `Task_RecordDefault`
- `Task_RecordDefault` → `End_Defaulted`
- `Msg_Commit1` → `Gateway_CommitMerge`
- `Msg_Commit2` → `Gateway_CommitMerge`
- `Msg_Commit3` → `Gateway_CommitMerge`
- `Msg_Commit4` → `Gateway_CommitMerge`
- `Gateway_CommitMerge` → `Task_RecordCommit`
- `Task_RecordCommit` → `End_Committed`

## Job types → workers

- `autoFollowUp` → worker `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#autoFollowUp` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:68 — see `workers/autoFollowUp.md`
- `recordLPResponse` → worker `worker:cmn-assetmgmt:com.vishworks.assetmgmt.handlers.Handlers#recordLPResponse` at src/main/java/com/vishworks/assetmgmt/handlers/Handlers.java:69 — see `workers/recordLPResponse.md`
