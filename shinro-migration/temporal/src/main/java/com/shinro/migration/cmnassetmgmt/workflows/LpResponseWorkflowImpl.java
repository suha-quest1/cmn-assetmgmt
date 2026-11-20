package com.shinro.migration.cmnassetmgmt.workflows;

import com.shinro.migration.cmnassetmgmt.activities.LpResponseActivities;
import com.shinro.migration.cmnassetmgmt.domain.Models.FollowUpInput;
import com.shinro.migration.cmnassetmgmt.domain.Models.LPResponse;
import com.shinro.migration.cmnassetmgmt.domain.Models.LpCommitmentSignal;
import com.shinro.migration.cmnassetmgmt.domain.Models.LpRequest;
import com.shinro.migration.cmnassetmgmt.domain.Models.RecordInput;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class LpResponseWorkflowImpl implements LpResponseWorkflow {
  // Suggested retry policy of the worker md: maxAttempts=3, no non-retryable codes.
  private final LpResponseActivities activities = Workflow.newActivityStub(
      LpResponseActivities.class,
      ActivityOptions.newBuilder()
          .setStartToCloseTimeout(Duration.ofSeconds(60))
          .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
          .build());

  private LpCommitmentSignal commitment;

  @Override
  public LPResponse run(LpRequest req) {
    Duration[] waits = {
        Duration.parse(req.waitStage1), Duration.parse(req.waitStage2),
        Duration.parse(req.waitStage3), Duration.parse(req.waitFinal)
    };
    int followUps = 0;
    // Event-based gateways Await1..Await4: the commitment message races the stage timer.
    for (int i = 0; i < waits.length; i++) {
      boolean committed = Workflow.await(waits[i], () -> commitment != null);
      if (committed) {
        return record(req, "committed", followUps);
      }
      if (i < 3) {
        followUps = activities.autoFollowUp(new FollowUpInput(req.callId, req.lp, i + 1));
      }
    }
    // Timer_Deadline: record the default.
    return record(req, "defaulted", followUps);
  }

  private LPResponse record(LpRequest req, String status, int followUps) {
    RecordInput in = new RecordInput();
    in.callId = req.callId;
    in.lp = req.lp;
    in.status = status;
    in.followUps = followUps;
    if (commitment != null) {
      in.responseStatus = commitment.responseStatus;
      in.amountUSD = commitment.amountUSD;
    }
    return activities.recordLPResponse(in);
  }

  @Override
  public void lpCommitment(LpCommitmentSignal signal) {
    if (commitment == null) {
      commitment = signal == null ? new LpCommitmentSignal() : signal;
    }
  }
}
