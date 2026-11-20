package com.shinro.migration.cmnassetmgmt.workflows;

import com.shinro.migration.cmnassetmgmt.domain.Models.LPResponse;
import com.shinro.migration.cmnassetmgmt.domain.Models.LpCommitmentSignal;
import com.shinro.migration.cmnassetmgmt.domain.Models.LpRequest;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** BPMN process lp-response: one instance per LP. */
@WorkflowInterface
public interface LpResponseWorkflow {
  @WorkflowMethod(name = "lp-response")
  LPResponse run(LpRequest request);

  /** Message_LPCommitment (message name lpCommitment). */
  @SignalMethod(name = "lpCommitment")
  void lpCommitment(LpCommitmentSignal signal);
}
