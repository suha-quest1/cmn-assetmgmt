package com.shinro.migration.cmnassetmgmt.workflows;

import com.shinro.migration.cmnassetmgmt.activities.CapitalCallActivities;
import com.shinro.migration.cmnassetmgmt.domain.Models;
import com.shinro.migration.cmnassetmgmt.domain.Models.AggregateResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.CallState;
import com.shinro.migration.cmnassetmgmt.domain.Models.CapitalCallInput;
import com.shinro.migration.cmnassetmgmt.domain.Models.CapitalCallResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.FinalizeInput;
import com.shinro.migration.cmnassetmgmt.domain.Models.FinalizeResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.GPDecision;
import com.shinro.migration.cmnassetmgmt.domain.Models.GpDecisionSignal;
import com.shinro.migration.cmnassetmgmt.domain.Models.GpResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.GpScope;
import com.shinro.migration.cmnassetmgmt.domain.Models.IssueResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.LP;
import com.shinro.migration.cmnassetmgmt.domain.Models.LPResponse;
import com.shinro.migration.cmnassetmgmt.domain.Models.LpCommitmentSignal;
import com.shinro.migration.cmnassetmgmt.domain.Models.LpRequest;
import com.shinro.migration.cmnassetmgmt.domain.Models.ReportResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.RiskResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CapitalCallWorkflowImpl implements CapitalCallWorkflow {
  // Suggested retry policy of the worker mds: maxAttempts=3, no non-retryable codes.
  private final CapitalCallActivities act = Workflow.newActivityStub(
      CapitalCallActivities.class,
      ActivityOptions.newBuilder()
          .setStartToCloseTimeout(Duration.ofMinutes(2))
          .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
          .build());

  private boolean force;
  private boolean cancel;
  private final Map<String, GpDecisionSignal> gpSignals = new HashMap<>();
  private final Map<String, LpCommitmentSignal> lpSignals = new HashMap<>();
  private final Map<String, LpResponseWorkflow> children = new HashMap<>();

  @Override
  public CapitalCallResult run(CapitalCallInput in) {
    IssueResult issued = act.issueCapitalCall(in);

    CallState st = new CallState();
    st.callId = in.callId;
    st.fundId = in.fundId;
    st.targetAmountUSD = in.targetAmountUSD;
    st.deadlineDays = issued.deadlineDays;
    st.lpList = issued.lpList;
    act.notifyLPs(st);

    // Multi-instance call activity: one lp-response child per LP.
    String parentId = Workflow.getInfo().getWorkflowId();
    List<Promise<LPResponse>> promises = new ArrayList<>();
    for (LP lp : issued.lpList) {
      LpResponseWorkflow child = Workflow.newChildWorkflowStub(
          LpResponseWorkflow.class,
          ChildWorkflowOptions.newBuilder()
              .setTaskQueue(Models.PROCESS_LP_RESPONSE)
              .setWorkflowId(parentId + ":" + lp.lpId)
              .build());
      LpRequest req = new LpRequest();
      req.callId = in.callId;
      req.lp = lp;
      req.waitStage1 = issued.waitStage1;
      req.waitStage2 = issued.waitStage2;
      req.waitStage3 = issued.waitStage3;
      req.waitFinal = issued.waitFinal;
      promises.add(Async.function(child::run, req));
      children.put(lp.lpId, child);
    }
    // Forward commitments that arrived before the children started.
    for (Map.Entry<String, LpCommitmentSignal> e : lpSignals.entrySet()) {
      LpResponseWorkflow c = children.get(e.getKey());
      if (c != null) {
        c.lpCommitment(e.getValue());
      }
    }
    Promise<Void> all = Promise.allOf(promises);

    // Boundary events: callDeadline timer, forceSettlement and cancelCall messages.
    boolean done = Workflow.await(Duration.parse(issued.callDeadline), () -> all.isCompleted() || force || cancel);

    if (cancel) {
      act.markCallCancelled(st);
      CapitalCallResult r = new CapitalCallResult();
      r.status = "cancelled";
      r.callId = in.callId;
      return r;
    }
    if (done && all.isCompleted() && !force) {
      List<LPResponse> rs = new ArrayList<>();
      for (Promise<LPResponse> p : promises) {
        rs.add(p.get());
      }
      st.lpResponses = rs;
    } else {
      String reason = force ? "force-settlement" : "hard-deadline";
      FinalizeResult fr = act.finalizeResponses(new FinalizeInput(st, reason));
      st.lpResponses = fr.lpResponses == null ? new ArrayList<>() : fr.lpResponses;
      st.settlementMode = reason;
    }

    AggregateResult ag = act.aggregateLiquidity(st);
    st.totalCommitted = ag.totalCommitted;
    st.gapUSD = ag.gapUSD;
    st.gapPercent = ag.gapPercent;

    RiskResult risk = act.predictDefaultRisk(st);
    st.lpResponses = risk.lpResponses;
    st.highRiskLPs = risk.highRiskLPs == null ? new ArrayList<>() : risk.highRiskLPs;
    st.portfolioRisk = act.scoreLPPortfolio(st);

    // Parallel gateway: bridge financing and GP review run side by side.
    CallState snap = st;
    Promise<Void> bridge = Async.procedure(() -> {
      if (snap.gapPercent > Models.BRIDGE_THRESHOLD_PERCENT) {
        snap.bridgeResult = act.triggerBridge(snap);
        snap.bridgeTriggered = true;
      }
    });
    List<Promise<GPDecision>> reviews = new ArrayList<>();
    for (LPResponse hr : st.highRiskLPs) {
      reviews.add(Async.function(() -> review(snap.callId, hr)));
    }
    bridge.get();
    for (Promise<GPDecision> p : reviews) {
      st.gpDecisions.add(p.get());
    }

    int wires = act.settleAndReconcile(st);
    ReportResult rep = act.emitLiquidityReport(st);

    CapitalCallResult r = new CapitalCallResult();
    r.status = "completed";
    r.callId = in.callId;
    r.settlementMode = st.settlementMode;
    r.totalCommitted = st.totalCommitted;
    r.gapUSD = st.gapUSD;
    r.gapPercent = st.gapPercent;
    r.bridgeTriggered = st.bridgeTriggered;
    r.wiresSettled = wires;
    r.lpResponses = st.lpResponses;
    r.gpDecisions = st.gpDecisions;
    r.reportPdf = rep.reportPdf;
    r.reportJson = rep.reportJson;
    return r;
  }

  /** GP review sub-process for one high-risk LP. The user task has no timer in the BPMN. */
  private GPDecision review(String callId, LPResponse hr) {
    GpScope scope = new GpScope();
    scope.callId = callId;
    scope.highRiskLp = hr;
    act.escalateToGP(scope);
    Workflow.await(() -> gpSignals.containsKey(hr.lpId));
    GpDecisionSignal sig = gpSignals.get(hr.lpId);
    scope.gpAction = sig.action;
    scope.gpName = sig.gpName;
    scope.gpComment = sig.comment;
    GpResult gr = act.applyGPDecision(scope);
    GPDecision d = new GPDecision();
    d.lpId = hr.lpId;
    d.riskScore = hr.riskScore;
    d.action = gr.gpAction;
    d.gpName = gr.gpName;
    return d;
  }

  @Override
  public void lpCommitment(LpCommitmentSignal signal) {
    if (signal == null || lpSignals.containsKey(signal.lpId)) {
      return;
    }
    lpSignals.put(signal.lpId, signal);
    LpResponseWorkflow c = children.get(signal.lpId);
    if (c != null) {
      c.lpCommitment(signal);
    }
  }

  @Override
  public void gpDecision(GpDecisionSignal signal) {
    if (signal != null) {
      gpSignals.putIfAbsent(signal.lpId, signal);
    }
  }

  @Override
  public void forceSettlement() {
    force = true;
  }

  @Override
  public void cancelCall() {
    cancel = true;
  }
}
