package com.shinro.migration.cmnassetmgmt.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Data types of the capital-call and lp-response processes. Field names are the wire names
 * (same as the Camunda process variables).
 */
public final class Models {
  private Models() {}

  public static final String PROCESS_CAPITAL_CALL = "capital-call";
  public static final String PROCESS_LP_RESPONSE = "lp-response";

  /** Signal names. They match the Camunda message names. */
  public static final String SIG_LP_COMMITMENT = "lpCommitment";
  public static final String SIG_GP_DECISION = "gpDecision";
  public static final String SIG_FORCE_SETTLEMENT = "forceSettlement";
  public static final String SIG_CANCEL_CALL = "cancelCall";

  public static final double BRIDGE_THRESHOLD_PERCENT = 10.0;
  public static final double HIGH_RISK_THRESHOLD = 0.7;

  /** Start payload of the capital-call process. */
  public static class CapitalCallInput {
    public String callId = "";
    public String fundId = "";
    public double targetAmountUSD;
    public List<LP> lpList;
    public int deadlineDays;
    /** Compresses "business days" for demos (0 = real days). */
    public int secondsPerDay;

    public CapitalCallInput() {}
  }

  /** A Limited Partner. */
  public static class LP {
    public String lpId = "";
    public double commitmentUSD;
    public String email = "";
    public double drawAmountUSD;

    public LP() {}

    public LP(String lpId, double commitmentUSD, String email, double drawAmountUSD) {
      this.lpId = lpId;
      this.commitmentUSD = commitmentUSD;
      this.email = email;
      this.drawAmountUSD = drawAmountUSD;
    }
  }

  /** The result of one lp-response child process. */
  public static class LPResponse {
    public String lpId = "";
    public String email = "";
    public double commitmentUSD;
    public double drawAmountUSD;
    /** committed | deferred | defaulted. */
    public String status = "";
    public double amountUSD;
    public double riskScore;
    public int followUps;

    public LPResponse() {}

    public LPResponse copy() {
      LPResponse r = new LPResponse();
      r.lpId = lpId;
      r.email = email;
      r.commitmentUSD = commitmentUSD;
      r.drawAmountUSD = drawAmountUSD;
      r.status = status;
      r.amountUSD = amountUSD;
      r.riskScore = riskScore;
      r.followUps = followUps;
      return r;
    }
  }

  public static class BridgeResult {
    public String confirmationId = "";
    public double drawdownUSD;
    public double feeUSD;

    public BridgeResult() {}
  }

  public static class PortfolioRisk {
    public double concentrationScore;
    public List<String> topRiskyLPs;
    public double averageRiskScore;
    public double expectedLossUSD;

    public PortfolioRisk() {}
  }

  public static class GPDecision {
    public String lpId = "";
    public double riskScore;
    /** waive | enforce. */
    public String action = "";
    public String gpName = "";

    public GPDecision() {}
  }

  /** The union of process variables the capital-call activities read. */
  public static class CallState {
    public String callId = "";
    public String fundId = "";
    public double targetAmountUSD;
    public int deadlineDays;
    public List<LP> lpList;
    public List<LPResponse> lpResponses;
    public double totalCommitted;
    public double gapUSD;
    public double gapPercent;
    public boolean bridgeTriggered;
    public BridgeResult bridgeResult;
    public PortfolioRisk portfolioRisk;
    public List<LPResponse> highRiskLPs = new ArrayList<>();
    public List<GPDecision> gpDecisions = new ArrayList<>();
    public String settlementMode = "";

    public CallState() {}
  }

  /** Output of issueCapitalCall: the variables the Camunda worker returned. */
  public static class IssueResult {
    public List<LP> lpList;
    public Map<String, Double> drawAmounts;
    public int deadlineDays;
    public String waitStage1;
    public String waitStage2;
    public String waitStage3;
    public String waitFinal;
    public String callDeadline;
    public String deadlineAt;

    public IssueResult() {}
  }

  public static class FinalizeInput {
    public CallState state;
    /** force-settlement | hard-deadline (the BPMN task header "reason"). */
    public String reason = "";

    public FinalizeInput() {}

    public FinalizeInput(CallState state, String reason) {
      this.state = state;
      this.reason = reason;
    }
  }

  public static class FinalizeResult {
    /** Null when the call has no LP rows. */
    public List<LPResponse> lpResponses;
    public String settlementMode = "";

    public FinalizeResult() {}
  }

  public static class AggregateResult {
    public double totalCommitted;
    public double gapUSD;
    public double gapPercent;

    public AggregateResult() {}
  }

  public static class RiskResult {
    public List<LPResponse> lpResponses;
    public List<LPResponse> highRiskLPs;

    public RiskResult() {}
  }

  /** Variables of one GP review sub-process instance. */
  public static class GpScope {
    public String callId = "";
    public LPResponse highRiskLp = new LPResponse();
    public String gpAction = "";
    public String gpName = "";
    public String gpComment = "";

    public GpScope() {}
  }

  public static class GpResult {
    public String gpAction = "";
    public String gpName = "";

    public GpResult() {}
  }

  public static class ReportResult {
    public String reportPdf = "";
    public String reportJson = "";

    public ReportResult() {}
  }

  /** Signal payload for gpDecision (user task completion or API message). */
  public static class GpDecisionSignal {
    public String lpId = "";
    /** waive | enforce. */
    public String action = "";
    public String gpName = "";
    public String comment = "";

    public GpDecisionSignal() {}

    public GpDecisionSignal(String lpId, String action, String gpName, String comment) {
      this.lpId = lpId;
      this.action = action;
      this.gpName = gpName;
      this.comment = comment;
    }
  }

  /** Signal payload for lpCommitment. */
  public static class LpCommitmentSignal {
    /** Which LP committed. Read by the capital-call workflow, which routes the signal. */
    public String lpId = "";
    /** committed | deferred. */
    public String responseStatus = "committed";
    /** Optional confirmed amount. */
    public Double amountUSD;

    public LpCommitmentSignal() {}

    public LpCommitmentSignal(String lpId, String responseStatus, Double amountUSD) {
      this.lpId = lpId;
      this.responseStatus = responseStatus;
      this.amountUSD = amountUSD;
    }
  }

  /** Start payload of the lp-response child process. Wait values are ISO-8601 durations. */
  public static class LpRequest {
    public String callId = "";
    public LP lp = new LP();
    public String waitStage1 = "PT1S";
    public String waitStage2 = "PT1S";
    public String waitStage3 = "PT1S";
    public String waitFinal = "PT1S";

    public LpRequest() {}
  }

  /** Input of autoFollowUp: the BPMN task header "stage" is an explicit field. */
  public static class FollowUpInput {
    public String callId = "";
    public LP lp = new LP();
    public int stage;

    public FollowUpInput() {}

    public FollowUpInput(String callId, LP lp, int stage) {
      this.callId = callId;
      this.lp = lp;
      this.stage = stage;
    }
  }

  /** Input of recordLPResponse: the BPMN task header "status" is an explicit field. */
  public static class RecordInput {
    public String callId = "";
    public LP lp = new LP();
    /** The task header: committed | defaulted. */
    public String status = "";
    public String responseStatus = "";
    public Double amountUSD;
    public int followUps;

    public RecordInput() {}
  }

  /** Result of the capital-call workflow. */
  public static class CapitalCallResult {
    /** completed | cancelled. */
    public String status = "";
    public String callId = "";
    public String settlementMode = "";
    public double totalCommitted;
    public double gapUSD;
    public double gapPercent;
    public boolean bridgeTriggered;
    public int wiresSettled;
    public List<LPResponse> lpResponses;
    public List<GPDecision> gpDecisions = new ArrayList<>();
    public String reportPdf = "";
    public String reportJson = "";

    public CapitalCallResult() {}
  }
}
