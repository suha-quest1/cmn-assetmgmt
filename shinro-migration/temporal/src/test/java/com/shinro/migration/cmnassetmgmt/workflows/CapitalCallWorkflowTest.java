package com.shinro.migration.cmnassetmgmt.workflows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shinro.migration.cmnassetmgmt.activities.CapitalCallActivities;
import com.shinro.migration.cmnassetmgmt.activities.LpResponseActivities;
import com.shinro.migration.cmnassetmgmt.domain.Models.AggregateResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.BridgeResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.CallState;
import com.shinro.migration.cmnassetmgmt.domain.Models.CapitalCallInput;
import com.shinro.migration.cmnassetmgmt.domain.Models.CapitalCallResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.FinalizeInput;
import com.shinro.migration.cmnassetmgmt.domain.Models.FinalizeResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.GpDecisionSignal;
import com.shinro.migration.cmnassetmgmt.domain.Models.GpResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.IssueResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.LP;
import com.shinro.migration.cmnassetmgmt.domain.Models.LPResponse;
import com.shinro.migration.cmnassetmgmt.domain.Models.LpCommitmentSignal;
import com.shinro.migration.cmnassetmgmt.domain.Models.PortfolioRisk;
import com.shinro.migration.cmnassetmgmt.domain.Models.RecordInput;
import com.shinro.migration.cmnassetmgmt.domain.Models.ReportResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.RiskResult;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;

@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class CapitalCallWorkflowTest {
  private static CapitalCallInput input() {
    CapitalCallInput in = new CapitalCallInput();
    in.callId = "CC-1";
    in.fundId = "F-1";
    in.targetAmountUSD = 1000;
    in.lpList = List.of(new LP("LP-1", 500, "a@x.com", 0), new LP("LP-2", 500, "b@x.com", 0));
    in.deadlineDays = 10;
    in.secondsPerDay = 1;
    return in;
  }

  /** Mock activities. Behaviour depends only on the input of each call. */
  private static CapitalCallActivities mockActivities(String childWait, String callDeadline, double gapPercent, double riskOfLp1) {
    CapitalCallActivities a = mock(CapitalCallActivities.class, Mockito.withSettings().withoutAnnotations());
    when(a.issueCapitalCall(any())).thenAnswer(i -> {
      CapitalCallInput in = i.getArgument(0);
      IssueResult r = new IssueResult();
      r.lpList = new ArrayList<>();
      for (LP lp : in.lpList) {
        r.lpList.add(new LP(lp.lpId, lp.commitmentUSD, lp.email, in.targetAmountUSD * lp.commitmentUSD / 1000));
      }
      r.deadlineDays = 10;
      r.waitStage1 = childWait;
      r.waitStage2 = childWait;
      r.waitStage3 = childWait;
      r.waitFinal = childWait;
      r.callDeadline = callDeadline;
      return r;
    });
    when(a.finalizeResponses(any())).thenAnswer(i -> {
      FinalizeInput in = i.getArgument(0);
      FinalizeResult r = new FinalizeResult();
      r.lpResponses = new ArrayList<>();
      for (LP lp : in.state.lpList) {
        LPResponse x = new LPResponse();
        x.lpId = lp.lpId;
        x.status = "defaulted";
        r.lpResponses.add(x);
      }
      r.settlementMode = in.reason;
      return r;
    });
    when(a.aggregateLiquidity(any())).thenAnswer(i -> {
      CallState s = i.getArgument(0);
      double c = 0;
      for (LPResponse r : s.lpResponses) {
        if (r.status.equals("committed")) {
          c += r.amountUSD;
        }
      }
      AggregateResult r = new AggregateResult();
      r.totalCommitted = c;
      r.gapUSD = s.targetAmountUSD - c;
      r.gapPercent = gapPercent;
      return r;
    });
    when(a.predictDefaultRisk(any())).thenAnswer(i -> {
      CallState s = i.getArgument(0);
      RiskResult r = new RiskResult();
      r.lpResponses = s.lpResponses;
      r.highRiskLPs = new ArrayList<>();
      for (LPResponse x : s.lpResponses) {
        if (x.lpId.equals("LP-1")) {
          x.riskScore = riskOfLp1;
          if (riskOfLp1 > 0.7) {
            r.highRiskLPs.add(x.copy());
          }
        }
      }
      return r;
    });
    when(a.scoreLPPortfolio(any())).thenReturn(new PortfolioRisk());
    when(a.triggerBridge(any())).thenAnswer(i -> {
      BridgeResult b = new BridgeResult();
      b.confirmationId = "BR-1";
      b.drawdownUSD = 100;
      return b;
    });
    when(a.applyGPDecision(any())).thenAnswer(i -> {
      com.shinro.migration.cmnassetmgmt.domain.Models.GpScope s = i.getArgument(0);
      GpResult r = new GpResult();
      r.gpAction = s.gpAction;
      r.gpName = s.gpName;
      return r;
    });
    when(a.settleAndReconcile(any())).thenAnswer(i -> {
      CallState s = i.getArgument(0);
      int n = 0;
      for (LPResponse r : s.lpResponses) {
        if (r.status.equals("committed")) {
          n++;
        }
      }
      return n;
    });
    ReportResult rep = new ReportResult();
    rep.reportPdf = "/reports/CC-1.pdf";
    rep.reportJson = "/reports/CC-1.json";
    when(a.emitLiquidityReport(any())).thenReturn(rep);
    return a;
  }

  private static LpResponseActivities lpActivities() {
    LpResponseActivities a = mock(LpResponseActivities.class, Mockito.withSettings().withoutAnnotations());
    when(a.autoFollowUp(any())).thenAnswer(i -> ((com.shinro.migration.cmnassetmgmt.domain.Models.FollowUpInput) i.getArgument(0)).stage);
    when(a.recordLPResponse(any())).thenAnswer(i -> {
      RecordInput in = i.getArgument(0);
      LPResponse r = new LPResponse();
      r.lpId = in.lp.lpId;
      r.status = in.status;
      r.amountUSD = in.status.equals("committed") ? in.lp.drawAmountUSD : 0;
      r.followUps = in.followUps;
      return r;
    });
    return a;
  }

  private static CapitalCallResult run(CapitalCallActivities acts, Consumer<CapitalCallWorkflow> signals) {
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
    try {
      Worker cc = env.newWorker("capital-call");
      cc.registerWorkflowImplementationTypes(CapitalCallWorkflowImpl.class);
      cc.registerActivitiesImplementations(acts);
      Worker lp = env.newWorker("lp-response");
      lp.registerWorkflowImplementationTypes(LpResponseWorkflowImpl.class);
      lp.registerActivitiesImplementations(lpActivities());
      env.start();
      WorkflowClient client = env.getWorkflowClient();
      CapitalCallWorkflow wf = client.newWorkflowStub(CapitalCallWorkflow.class,
          WorkflowOptions.newBuilder().setTaskQueue("capital-call").build());
      WorkflowClient.start(wf::run, input());
      if (signals != null) {
        signals.accept(wf);
      }
      return WorkflowStub.fromTyped(wf).getResult(CapitalCallResult.class);
    } finally {
      env.close();
    }
  }

  @Test
  void happyPathNoBridgeNoHighRisk() {
    // Both LPs commit through the parent signal. Gap is 0, so there is no bridge.
    CapitalCallResult r = run(mockActivities("PT1H", "PT5H", 0, 0.1), wf -> {
      wf.lpCommitment(new LpCommitmentSignal("LP-1", "committed", null));
      wf.lpCommitment(new LpCommitmentSignal("LP-2", "committed", null));
    });
    assertEquals("completed", r.status);
    assertEquals(2, r.wiresSettled);
    assertFalse(r.bridgeTriggered);
    assertTrue(r.gpDecisions.isEmpty());
    assertEquals("/reports/CC-1.pdf", r.reportPdf);
  }

  @Test
  void largeGapTriggersBridge() {
    CapitalCallResult r = run(mockActivities("PT1S", "PT1H", 40, 0.1), null);
    assertEquals("completed", r.status);
    assertTrue(r.bridgeTriggered);
  }

  @Test
  void highRiskLpWaitsForGpDecision() {
    CapitalCallResult r = run(mockActivities("PT1S", "PT1H", 0, 0.9),
        wf -> wf.gpDecision(new GpDecisionSignal("LP-1", "enforce", "Pat", "ok")));
    assertEquals("completed", r.status);
    assertEquals(1, r.gpDecisions.size());
    assertEquals("enforce", r.gpDecisions.get(0).action);
    assertEquals("Pat", r.gpDecisions.get(0).gpName);
  }

  @Test
  void cancelCallEndsCancelled() {
    CapitalCallResult r = run(mockActivities("PT1H", "PT5H", 0, 0.1), CapitalCallWorkflow::cancelCall);
    assertEquals("cancelled", r.status);
  }

  @Test
  void forceSettlementFinalizesEarly() {
    CapitalCallResult r = run(mockActivities("PT1H", "PT5H", 0, 0.1), CapitalCallWorkflow::forceSettlement);
    assertEquals("completed", r.status);
    assertEquals("force-settlement", r.settlementMode);
  }

  @Test
  void callDeadlineFinalizesWithHardDeadline() {
    CapitalCallResult r = run(mockActivities("PT1H", "PT10S", 0, 0.1), null);
    assertEquals("completed", r.status);
    assertEquals("hard-deadline", r.settlementMode);
  }
}
