package com.shinro.migration.cmnassetmgmt.workflows;

import com.shinro.migration.cmnassetmgmt.domain.Models.CapitalCallInput;
import com.shinro.migration.cmnassetmgmt.domain.Models.CapitalCallResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.GpDecisionSignal;
import com.shinro.migration.cmnassetmgmt.domain.Models.LpCommitmentSignal;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** BPMN process capital-call. */
@WorkflowInterface
public interface CapitalCallWorkflow {
  @WorkflowMethod(name = "capital-call")
  CapitalCallResult run(CapitalCallInput request);

  /** Message lpCommitment. The workflow routes it to the lp-response child of that LP. */
  @SignalMethod(name = "lpCommitment")
  void lpCommitment(LpCommitmentSignal signal);

  /** Message gpDecision: completes the GP approval user task of one LP. */
  @SignalMethod(name = "gpDecision")
  void gpDecision(GpDecisionSignal signal);

  /** Message forceSettlement: closes the response window early. */
  @SignalMethod(name = "forceSettlement")
  void forceSettlement();

  /** Message cancelCall: cancels the call. */
  @SignalMethod(name = "cancelCall")
  void cancelCall();
}
