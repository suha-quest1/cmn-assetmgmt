package com.shinro.migration.cmnassetmgmt.activities;

import com.shinro.migration.cmnassetmgmt.domain.Models.AggregateResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.BridgeResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.CallState;
import com.shinro.migration.cmnassetmgmt.domain.Models.CapitalCallInput;
import com.shinro.migration.cmnassetmgmt.domain.Models.FinalizeInput;
import com.shinro.migration.cmnassetmgmt.domain.Models.FinalizeResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.GpResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.GpScope;
import com.shinro.migration.cmnassetmgmt.domain.Models.IssueResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.PortfolioRisk;
import com.shinro.migration.cmnassetmgmt.domain.Models.ReportResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.RiskResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** One activity per Camunda job worker of the capital-call process. */
@ActivityInterface
public interface CapitalCallActivities {
  @ActivityMethod
  IssueResult issueCapitalCall(CapitalCallInput in);

  @ActivityMethod
  void notifyLPs(CallState in);

  /** Used by both "Close response window" tasks. The reason is the BPMN task header. */
  @ActivityMethod
  FinalizeResult finalizeResponses(FinalizeInput in);

  @ActivityMethod
  AggregateResult aggregateLiquidity(CallState in);

  @ActivityMethod
  RiskResult predictDefaultRisk(CallState in);

  @ActivityMethod
  PortfolioRisk scoreLPPortfolio(CallState in);

  @ActivityMethod
  BridgeResult triggerBridge(CallState in);

  @ActivityMethod
  void escalateToGP(GpScope in);

  @ActivityMethod
  GpResult applyGPDecision(GpScope in);

  /** Returns the number of wire transfers booked. */
  @ActivityMethod
  int settleAndReconcile(CallState in);

  @ActivityMethod
  ReportResult emitLiquidityReport(CallState in);

  @ActivityMethod
  void markCallCancelled(CallState in);
}
