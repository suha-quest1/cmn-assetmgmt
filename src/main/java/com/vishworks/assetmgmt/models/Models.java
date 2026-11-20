package com.vishworks.assetmgmt.models;

/** Process, message and job-type identifiers shared with the BPMN models in {@code bpmn/}. */
public final class Models {
  private Models() {}

  public static final String PROCESS_CAPITAL_CALL = "capital-call";
  public static final String PROCESS_LP_RESPONSE = "lp-response";

  public static final String MSG_LP_COMMITMENT = "lpCommitment"; // correlationKey = callId:lpId
  public static final String MSG_GP_DECISION = "gpDecision"; // correlationKey = callId:lpId
  public static final String MSG_FORCE_SETTLEMENT = "forceSettlement"; // correlationKey = callId
  public static final String MSG_CANCEL_CALL = "cancelCall"; // correlationKey = callId

  public static final double BRIDGE_THRESHOLD_PERCENT = 10.0;
  public static final double HIGH_RISK_THRESHOLD = 0.7;
}
