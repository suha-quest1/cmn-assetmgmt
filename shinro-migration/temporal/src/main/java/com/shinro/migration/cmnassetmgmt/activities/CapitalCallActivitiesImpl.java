package com.shinro.migration.cmnassetmgmt.activities;

import static com.shinro.migration.cmnassetmgmt.activities.Fmt.round2;
import static com.shinro.migration.cmnassetmgmt.activities.Fmt.sprintf;
import static com.shinro.migration.cmnassetmgmt.activities.Support.m;

import com.fasterxml.jackson.core.type.TypeReference;
import com.shinro.migration.cmnassetmgmt.domain.Models;
import com.shinro.migration.cmnassetmgmt.domain.Models.AggregateResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.BridgeResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.CallState;
import com.shinro.migration.cmnassetmgmt.domain.Models.CapitalCallInput;
import com.shinro.migration.cmnassetmgmt.domain.Models.FinalizeInput;
import com.shinro.migration.cmnassetmgmt.domain.Models.FinalizeResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.GPDecision;
import com.shinro.migration.cmnassetmgmt.domain.Models.GpResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.GpScope;
import com.shinro.migration.cmnassetmgmt.domain.Models.IssueResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.LP;
import com.shinro.migration.cmnassetmgmt.domain.Models.LPResponse;
import com.shinro.migration.cmnassetmgmt.domain.Models.PortfolioRisk;
import com.shinro.migration.cmnassetmgmt.domain.Models.ReportResult;
import com.shinro.migration.cmnassetmgmt.domain.Models.RiskResult;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Activities of the capital-call process. Each method is the translation of one Camunda job
 * worker in Handlers.java. A failure is a plain retryable failure, as the source job
 * failed with retries - 1. The source never raised a BPMN error code.
 */
public class CapitalCallActivitiesImpl implements CapitalCallActivities {
  private final Support s;

  public CapitalCallActivitiesImpl(Support s) {
    this.s = s;
  }

  static <T> List<T> nz(List<T> l) {
    return l == null ? List.of() : l;
  }

  static String isoSeconds(long sec) {
    if (sec < 1) {
      sec = 1;
    }
    return "PT" + sec + "S";
  }

  static RuntimeException fail(String msg, Throwable cause) {
    return ApplicationFailure.newFailureWithCause(msg, "ActivityFailed", cause);
  }

  // ─── 1. issueCapitalCall ───────────────────────────────────────────────

  @Override
  public IssueResult issueCapitalCall(CapitalCallInput in) {
    if (in.callId == null || in.callId.isEmpty() || in.lpList == null || in.lpList.isEmpty() || in.targetAmountUSD <= 0) {
      // The input can never become valid on a retry, so this failure is non-retryable.
      throw ApplicationFailure.newNonRetryableFailure(
          "invalid capital call input: callId, lpList and targetAmountUSD are required", "InvalidInput");
    }
    if (in.deadlineDays <= 0) {
      in.deadlineDays = 10;
    }
    long spd = in.secondsPerDay;
    if (spd <= 0) {
      spd = 86400;
    }
    double total = 0.0;
    for (LP lp : in.lpList) {
      total += lp.commitmentUSD;
    }
    Map<String, Double> draws = new TreeMap<>();
    for (LP lp : in.lpList) {
      double d = round2(in.targetAmountUSD * lp.commitmentUSD / total);
      lp.drawAmountUSD = d;
      draws.put(lp.lpId, d);
    }
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime deadline = now.plusSeconds((long) in.deadlineDays * spd);
    String pik = processKey();
    try {
      // Keyed on callId: ON CONFLICT upserts, so a retried activity does not duplicate rows.
      s.db.transaction(tx -> {
        try {
          tx.exec("INSERT INTO capital_calls (call_id, fund_id, process_instance_key, target_amount_usd, status, deadline_days, seconds_per_day, deadline_date) "
              + "VALUES ($1,$2,$3,$4,'issued',$5,$6,$7) "
              + "ON CONFLICT (call_id) DO UPDATE SET process_instance_key=EXCLUDED.process_instance_key",
              in.callId, in.fundId, pik, in.targetAmountUSD, in.deadlineDays, in.secondsPerDay, deadline);
        } catch (java.sql.SQLException e) {
          throw new IOException("insert capital_calls: " + e.getMessage(), e);
        }
        for (LP lp : in.lpList) {
          try {
            tx.exec("INSERT INTO capital_call_lps (call_id, lp_id, email, commitment_usd, draw_amount_usd, status) "
                + "VALUES ($1,$2,$3,$4,$5,'issued') ON CONFLICT (call_id, lp_id) DO NOTHING",
                in.callId, lp.lpId, lp.email, lp.commitmentUSD, lp.drawAmountUSD);
          } catch (java.sql.SQLException e) {
            throw new IOException("insert capital_call_lps: " + e.getMessage(), e);
          }
        }
      });
    } catch (Exception e) {
      throw fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
    }
    s.event(in.callId, "", "issueCapitalCall", sprintf("Call issued for $%.0f across %d LPs (deadline %d days)",
        in.targetAmountUSD, in.lpList.size(), in.deadlineDays));

    long fin = (in.deadlineDays - 10L) * spd;
    IssueResult out = new IssueResult();
    out.lpList = in.lpList;
    out.drawAmounts = draws;
    out.deadlineDays = in.deadlineDays;
    out.waitStage1 = isoSeconds(3 * spd);
    out.waitStage2 = isoSeconds(4 * spd);
    out.waitStage3 = isoSeconds(3 * spd);
    out.waitFinal = isoSeconds(fin);
    out.callDeadline = isoSeconds((in.deadlineDays + 3L) * spd);
    out.deadlineAt = Fmt.rfc3339Utc(deadline.atZone(ZoneId.systemDefault()).toInstant());
    return out;
  }

  /** The workflow id stands in for the Camunda process instance key. */
  static String processKey() {
    try {
      return Activity.getExecutionContext().getInfo().getWorkflowId();
    } catch (RuntimeException e) {
      return "";
    }
  }

  // ─── 2. notifyLPs ──────────────────────────────────────────────────────

  @Override
  public void notifyLPs(CallState in) {
    List<LP> lps = nz(in.lpList);
    for (LP lp : lps) {
      // Idempotency key callId:lpId. SES must dedupe on it for retries to be safe.
      s.email(m(
          "to", lp.email,
          "subject", sprintf("Capital Call %s — Action Required", in.callId),
          "body", sprintf("Dear %s, fund %s has issued capital call %s. Your pro-rata draw is $%.2f. Confirm via the secure portal: %s/?call=%s&lp=%s",
              lp.lpId, in.fundId, in.callId, lp.drawAmountUSD, s.portalUrl, in.callId, lp.lpId),
          "callId", in.callId, "lpId", lp.lpId, "type", "notification"),
          in.callId + ":" + lp.lpId);
    }
    try {
      s.db.exec("UPDATE capital_call_lps SET status='notified', updated_at=NOW() WHERE call_id=$1 AND status='issued'", in.callId);
    } catch (java.sql.SQLException e) {
      throw fail(e.getMessage(), e);
    }
    s.setCallStatus(in.callId, "awaiting_lps");
    s.event(in.callId, "", "notifyLPs", sprintf("Batch notice sent to %d LPs via SES", lps.size()));
  }

  // ─── 4. finalizeResponses ──────────────────────────────────────────────

  @Override
  public FinalizeResult finalizeResponses(FinalizeInput input) {
    CallState in = input.state;
    String reason = input.reason == null ? "" : input.reason;
    try {
      List<LPResponse> out = s.db.query(
          "SELECT lp_id, COALESCE(email,''), commitment_usd::float8, draw_amount_usd::float8, committed_usd::float8, status, followup_stage FROM capital_call_lps WHERE call_id=$1 ORDER BY lp_id",
          rs -> {
            LPResponse r = new LPResponse();
            r.lpId = rs.getString(1);
            r.email = rs.getString(2);
            r.commitmentUSD = rs.getDouble(3);
            r.drawAmountUSD = rs.getDouble(4);
            r.amountUSD = rs.getDouble(5);
            r.status = rs.getString(6);
            r.followUps = rs.getInt(7);
            if (!r.status.equals("committed") && !r.status.equals("deferred")) {
              r.status = "defaulted";
              r.amountUSD = 0;
            }
            return r;
          },
          in.callId);
      s.db.exec("UPDATE capital_call_lps SET status='defaulted', committed_usd=0 WHERE call_id=$1 AND status NOT IN ('committed','deferred')", in.callId);
      s.event(in.callId, "", "finalizeResponses", "Response window closed early (" + reason + "); outstanding LPs marked defaulted");
      FinalizeResult res = new FinalizeResult();
      // No rows is reported as null, as the BPMN gateway tested the variable for null.
      res.lpResponses = out.isEmpty() ? null : out;
      res.settlementMode = reason;
      return res;
    } catch (java.sql.SQLException e) {
      throw fail(e.getMessage(), e);
    }
  }

  // ─── 5. aggregateLiquidity ─────────────────────────────────────────────

  @Override
  public AggregateResult aggregateLiquidity(CallState in) {
    double committed = 0.0;
    for (LPResponse r : nz(in.lpResponses)) {
      if (r.status.equals("committed")) {
        committed += r.amountUSD;
      }
    }
    double gap = Math.max(0, in.targetAmountUSD - committed);
    double pct = 0.0;
    if (in.targetAmountUSD > 0) {
      pct = round2(gap / in.targetAmountUSD * 100);
    }
    try {
      // Keyed on callId: the UPDATE sets absolute values, so a retry is safe.
      s.db.exec("UPDATE capital_calls SET status='aggregated', received_amount_usd=$2, gap_usd=$3, gap_percent=$4, updated_at=NOW() WHERE call_id=$1",
          in.callId, committed, gap, pct);
    } catch (java.sql.SQLException e) {
      throw fail(e.getMessage(), e);
    }
    s.event(in.callId, "", "aggregateLiquidity", sprintf("Committed $%.0f of $%.0f — gap $%.0f (%.2f%%)", committed, in.targetAmountUSD, gap, pct));
    AggregateResult r = new AggregateResult();
    r.totalCommitted = round2(committed);
    r.gapUSD = round2(gap);
    r.gapPercent = pct;
    return r;
  }

  // ─── 6. predictDefaultRisk ─────────────────────────────────────────────

  /** One element of the ML scorer response. */
  public static class Score {
    public String lpId = "";
    public double riskScore;

    public Score() {}
  }

  @Override
  public RiskResult predictDefaultRisk(CallState in) {
    List<Score> scores;
    try {
      // Idempotency key callId. The scorer must dedupe on it. Scoring is a pure function.
      scores = s.postJSON(s.mlScorerUrl + "/score", m("callId", in.callId, "lpResponses", in.lpResponses),
          new TypeReference<List<Score>>() {}, in.callId);
    } catch (IOException e) {
      throw fail("ml-scorer: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw fail("ml-scorer: interrupted", e);
    }
    Map<String, Double> sc = new LinkedHashMap<>();
    for (Score x : nz(scores)) {
      sc.put(x.lpId, x.riskScore);
    }
    List<LPResponse> high = new ArrayList<>();
    List<LPResponse> responses = in.lpResponses;
    try {
      for (LPResponse r : nz(responses)) {
        r.riskScore = sc.getOrDefault(r.lpId, 0.0);
        s.db.exec("UPDATE capital_call_lps SET risk_score=$3 WHERE call_id=$1 AND lp_id=$2", in.callId, r.lpId, r.riskScore);
        if (r.status.equals("committed") && r.riskScore > Models.HIGH_RISK_THRESHOLD) {
          high.add(r.copy());
        }
      }
    } catch (java.sql.SQLException e) {
      throw fail(e.getMessage(), e);
    }
    s.setCallStatus(in.callId, "risk_scored");
    s.event(in.callId, "", "predictDefaultRisk", sprintf("Scored %d LPs; %d committed LP(s) above %.1f risk threshold",
        nz(responses).size(), high.size(), Models.HIGH_RISK_THRESHOLD));
    RiskResult out = new RiskResult();
    out.lpResponses = responses;
    out.highRiskLPs = high;
    return out;
  }

  // ─── 6b. scoreLPPortfolio ──────────────────────────────────────────────

  @Override
  public PortfolioRisk scoreLPPortfolio(CallState in) {
    List<LPResponse> rs = nz(in.lpResponses);
    double total = 0.0;
    for (LPResponse r : rs) {
      total += r.amountUSD;
    }
    double hhi = 0.0, sumRisk = 0.0, el = 0.0;
    for (LPResponse r : rs) {
      if (total > 0) {
        double sh = r.amountUSD / total;
        hhi += sh * sh;
      }
      sumRisk += r.riskScore;
      el += r.amountUSD * r.riskScore;
    }
    List<LPResponse> sorted = new ArrayList<>(rs);
    sorted.sort((a, b) -> Double.compare(b.riskScore, a.riskScore));
    List<String> top = new ArrayList<>();
    for (int i = 0; i < sorted.size() && i < 3; i++) {
      if (sorted.get(i).riskScore > 0.5) {
        top.add(sorted.get(i).lpId);
      }
    }
    PortfolioRisk pr = new PortfolioRisk();
    pr.concentrationScore = Fmt.round(hhi * 10000) / 10000;
    pr.topRiskyLPs = top;
    pr.averageRiskScore = Fmt.round(sumRisk / Math.max(1, rs.size()) * 10000) / 10000;
    pr.expectedLossUSD = round2(el);
    try {
      s.db.exec("UPDATE capital_calls SET concentration_hhi=$2 WHERE call_id=$1", in.callId, pr.concentrationScore);
    } catch (java.sql.SQLException e) {
      throw fail(e.getMessage(), e);
    }
    s.event(in.callId, "", "scoreLPPortfolio", sprintf("HHI %.4f, top risky %v", pr.concentrationScore, top));
    return pr;
  }

  // ─── 7. triggerBridge ──────────────────────────────────────────────────

  @Override
  public BridgeResult triggerBridge(CallState in) {
    BridgeResult br;
    try {
      // Idempotency key callId. The credit facility must dedupe on it, or a retry draws twice.
      br = s.postJSON(s.creditUrl + "/drawdown", m("callId", in.callId, "amount", in.gapUSD),
          new TypeReference<BridgeResult>() {}, in.callId);
    } catch (IOException e) {
      throw fail("credit facility: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw fail("credit facility: interrupted", e);
    }
    if (br == null) {
      br = new BridgeResult();
    }
    try {
      s.db.exec("UPDATE capital_calls SET bridge_triggered=TRUE, bridge_confirmation=$2, bridge_amount_usd=$3, bridge_fee_usd=$4 WHERE call_id=$1",
          in.callId, br.confirmationId, br.drawdownUSD, br.feeUSD);
    } catch (java.sql.SQLException e) {
      throw fail(e.getMessage(), e);
    }
    s.event(in.callId, "", "triggerBridge", sprintf("Credit line drawdown $%.0f confirmed %s (fee $%.0f)", br.drawdownUSD, br.confirmationId, br.feeUSD));
    return br;
  }

  // ─── 8. escalateToGP / applyGPDecision ─────────────────────────────────

  @Override
  public void escalateToGP(GpScope in) {
    LPResponse lp = in.highRiskLp;
    // Idempotency key callId:lpId. The alert channel must dedupe on it. The insert is keyed on it.
    s.email(m(
        "to", "#gp-investment-committee (Slack)",
        "subject", sprintf("[ESCALATION] High-risk LP %s on call %s", lp.lpId, in.callId),
        "body", sprintf("LP %s committed $%.2f but has default risk %.2f (> %.1f). Decide WAIVE or ENFORCE in the Temporal UI (%s) or via POST /api/capital-calls/%s/gp-decision.",
            lp.lpId, lp.amountUSD, lp.riskScore, Models.HIGH_RISK_THRESHOLD, s.temporalUiUrl, in.callId),
        "callId", in.callId, "lpId", lp.lpId, "type", "escalation"),
        in.callId + ":" + lp.lpId);
    try {
      s.db.exec("INSERT INTO gp_escalations (call_id, lp_id, risk_score) VALUES ($1,$2,$3) ON CONFLICT (call_id, lp_id) DO NOTHING",
          in.callId, lp.lpId, lp.riskScore);
    } catch (java.sql.SQLException e) {
      throw fail(e.getMessage(), e);
    }
    s.setCallStatus(in.callId, "awaiting_gp");
    s.event(in.callId, lp.lpId, "escalateToGP", sprintf("Escalated to GP (risk %.2f); awaiting approval gate", lp.riskScore));
  }

  @Override
  public GpResult applyGPDecision(GpScope in) {
    if (!"enforce".equals(in.gpAction)) {
      in.gpAction = "waive";
    }
    if (in.gpName == null || in.gpName.isEmpty()) {
      in.gpName = "GP Admin";
    }
    LPResponse lp = in.highRiskLp;
    String key = in.callId + ":" + lp.lpId; // idempotency key. The mail service must dedupe on it.
    if (in.gpAction.equals("enforce")) {
      s.email(m(
          "to", lp.email,
          "subject", sprintf("[COMPLIANCE WARNING] Capital Call %s — Enforcement Notice", in.callId),
          "body", sprintf("The General Partner (%s) has issued an enforcement notice on your $%.2f commitment. Your contribution remains recorded and is subject to compliance review.", in.gpName, lp.amountUSD),
          "callId", in.callId, "lpId", lp.lpId, "type", "enforcement_warning"), key + ":enforcement");
    }
    s.email(m(
        "to", "#gp-investment-committee (Slack)",
        "subject", sprintf("[GP DECISION] %s %s on call %s", in.gpName, in.gpAction, in.callId),
        "body", sprintf("Decision for LP %s: %s. Comment: %s", lp.lpId, in.gpAction, in.gpComment),
        "callId", in.callId, "lpId", lp.lpId, "type", "gp_decision"), key + ":decision");
    try {
      s.db.exec("UPDATE gp_escalations SET status='decided', action=$3, gp_name=$4, decided_at=NOW() WHERE call_id=$1 AND lp_id=$2",
          in.callId, lp.lpId, in.gpAction, in.gpName);
      s.db.exec("UPDATE capital_call_lps SET gp_action=$3 WHERE call_id=$1 AND lp_id=$2", in.callId, lp.lpId, in.gpAction);
    } catch (java.sql.SQLException e) {
      throw fail(e.getMessage(), e);
    }
    s.event(in.callId, lp.lpId, "gpApprovalGate", sprintf("GP %s decided: %s", in.gpName, in.gpAction));
    GpResult r = new GpResult();
    r.gpAction = in.gpAction;
    r.gpName = in.gpName;
    return r;
  }

  // ─── 9. settleAndReconcile ─────────────────────────────────────────────

  @Override
  public int settleAndReconcile(CallState in) {
    AtomicInteger wires = new AtomicInteger();
    try {
      // The ledger is rebuilt inside one transaction (DELETE then INSERT). A retry is safe.
      s.db.transaction(tx -> {
        tx.exec("DELETE FROM ledger_entries WHERE call_id=$1", in.callId);
        for (LPResponse r : nz(in.lpResponses)) {
          tx.exec("UPDATE capital_call_lps SET status=$3, committed_usd=$4 WHERE call_id=$1 AND lp_id=$2",
              in.callId, r.lpId, r.status, r.amountUSD);
          if (!r.status.equals("committed") || r.amountUSD <= 0) {
            continue;
          }
          String wire = sprintf("WIRE-%s-%s", in.callId, r.lpId);
          tx.exec("INSERT INTO ledger_entries (call_id, lp_id, debit_account, credit_account, amount_usd, entry_type, wire_reference) "
              + "VALUES ($1,$2,'fund/cash',$3,$4,'capital_contribution',$5)",
              in.callId, r.lpId, "lp/" + r.lpId + "/capital", r.amountUSD, wire);
          wires.incrementAndGet();
        }
        if (in.bridgeResult != null && in.bridgeResult.drawdownUSD > 0) {
          tx.exec("INSERT INTO ledger_entries (call_id, debit_account, credit_account, amount_usd, entry_type, wire_reference) "
              + "VALUES ($1,'fund/cash','liability/credit-facility',$2,'bridge_drawdown',$3)",
              in.callId, in.bridgeResult.drawdownUSD, in.bridgeResult.confirmationId);
          tx.exec("INSERT INTO ledger_entries (call_id, debit_account, credit_account, amount_usd, entry_type, wire_reference) "
              + "VALUES ($1,'expense/financing-fees','fund/cash',$2,'bridge_fee',$3)",
              in.callId, in.bridgeResult.feeUSD, in.bridgeResult.confirmationId);
        }
        tx.exec("UPDATE capital_calls SET status='settled', updated_at=NOW() WHERE call_id=$1", in.callId);
      });
    } catch (Exception e) {
      throw fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
    }
    s.event(in.callId, "", "settleAndReconcile", sprintf("%d wire transfers booked; double-entry ledger reconciled", wires.get()));
    return wires.get();
  }

  // ─── 10. emitLiquidityReport ───────────────────────────────────────────

  @Override
  public ReportResult emitLiquidityReport(CallState in) {
    try {
      String[] paths = writeReport(in, processKey());
      // Idempotency: the files and the UPDATE are keyed on callId, so a retry overwrites them.
      s.db.exec("UPDATE capital_calls SET status='completed', report_path=$2, updated_at=NOW() WHERE call_id=$1",
          in.callId, "/reports/" + in.callId + ".pdf");
      s.event(in.callId, "", "emitLiquidityReport", "Regulatory liquidity report emitted (PDF + JSON)");
      ReportResult r = new ReportResult();
      r.reportJson = paths[0];
      r.reportPdf = paths[1];
      return r;
    } catch (Exception e) {
      throw fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
    }
  }

  /** One row of the ledger section of the report. */
  static final class LedgerRow {
    public String lpId = "";
    public String debit = "";
    public String credit = "";
    public double amountUSD;
    public String entryType = "";
    public String wireReference = "";
  }

  String[] writeReport(CallState in, String pik) throws Exception {
    List<LedgerRow> ledger = s.db.query(
        "SELECT COALESCE(lp_id,''), debit_account, credit_account, amount_usd::float8, entry_type, COALESCE(wire_reference,'') FROM ledger_entries WHERE call_id=$1 ORDER BY id",
        rs -> {
          LedgerRow r = new LedgerRow();
          r.lpId = rs.getString(1);
          r.debit = rs.getString(2);
          r.credit = rs.getString(3);
          r.amountUSD = rs.getDouble(4);
          r.entryType = rs.getString(5);
          r.wireReference = rs.getString(6);
          return r;
        },
        in.callId);
    if (ledger.isEmpty()) {
      ledger = null;
    }
    ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
    Path dir = Path.of(s.reportDir);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(in.callId + ".json"), renderJson(in, pik, ledger, now), StandardCharsets.UTF_8);
    Files.write(dir.resolve(in.callId + ".pdf"), renderPdf(in, pik, ledger, now));
    return new String[] {"/reports/" + in.callId + ".json", "/reports/" + in.callId + ".pdf"};
  }

  static String renderJson(CallState in, String pik, List<LedgerRow> ledger, ZonedDateTime now) throws IOException {
    Map<String, Object> summary = new TreeMap<>();
    summary.put("targetAmountUSD", in.targetAmountUSD);
    summary.put("totalCommitted", in.totalCommitted);
    summary.put("gapUSD", in.gapUSD);
    summary.put("gapPercent", in.gapPercent);
    summary.put("bridgeTriggered", in.bridgeTriggered);
    Map<String, Object> doc = new TreeMap<>();
    doc.put("reportType", "LiquidityReport");
    doc.put("generatedAt", Fmt.rfc3339Utc(now.toInstant()));
    doc.put("orchestrator", "Temporal");
    doc.put("processInstanceKey", pik);
    doc.put("callId", in.callId);
    doc.put("fundId", in.fundId);
    doc.put("settlementMode", in.settlementMode);
    doc.put("summary", summary);
    doc.put("bridgeResult", in.bridgeResult);
    doc.put("portfolioRisk", in.portfolioRisk);
    doc.put("lpDetails", in.lpResponses);
    doc.put("gpDecisions", in.gpDecisions);
    doc.put("ledger", ledger);
    return Support.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(doc);
  }

  static byte[] renderPdf(CallState in, String pik, List<LedgerRow> ledger, ZonedDateTime now) {
    Pdf p = new Pdf();
    p.title("Capital Call Liquidity Report");
    p.text(sprintf("Call %s   |   Fund %s   |   Generated %s", in.callId, in.fundId,
        sprintf("%04d-%02d-%02d %02d:%02d UTC", now.getYear(), now.getMonthValue(), now.getDayOfMonth(), now.getHour(), now.getMinute())));
    p.text(sprintf("Orchestrated by Temporal - workflow %s   |   settlement mode: %s", pik, in.settlementMode));
    p.heading("1. Liquidity summary");
    p.mono(sprintf("%-28s %20s", "Target capital call", Fmt.usd(in.targetAmountUSD)));
    p.mono(sprintf("%-28s %20s", "LP capital committed", Fmt.usd(in.totalCommitted)));
    p.mono(sprintf("%-28s %20s  (%.2f%%)", "Liquidity gap", Fmt.usd(in.gapUSD), in.gapPercent));
    if (in.bridgeResult != null) {
      p.mono(sprintf("%-28s %20s  fee %s", "Bridge drawdown", Fmt.usd(in.bridgeResult.drawdownUSD), Fmt.usd(in.bridgeResult.feeUSD)));
      p.mono(sprintf("%-28s %s", "Facility confirmation", in.bridgeResult.confirmationId));
    } else {
      p.mono(sprintf("%-28s %s", "Bridge drawdown", "not required (gap <= 10%)"));
    }
    p.heading("2. LP responses and default-risk scores");
    p.mono(sprintf("%-7s %-10s %16s %16s %6s %9s", "LP", "Status", "Draw", "Funded", "Risk", "FollowUps"));
    for (LPResponse r : nz(in.lpResponses)) {
      p.mono(sprintf("%-7s %-10s %16s %16s %6.2f %9d", r.lpId, r.status, Fmt.usd(r.drawAmountUSD), Fmt.usd(r.amountUSD), r.riskScore, r.followUps));
    }
    if (in.portfolioRisk != null) {
      p.heading("3. Portfolio risk");
      p.mono(sprintf("Concentration (HHI): %.4f   Avg risk: %.4f   Expected loss: %s", in.portfolioRisk.concentrationScore,
          in.portfolioRisk.averageRiskScore, Fmt.usd(in.portfolioRisk.expectedLossUSD)));
      p.mono(sprintf("Top risky LPs: %s", Fmt.slice(in.portfolioRisk.topRiskyLPs)));
    }
    p.heading("4. GP escalations (human-in-the-loop)");
    List<GPDecision> decisions = nz(in.gpDecisions);
    if (decisions.isEmpty()) {
      p.text("No LP exceeded the 0.70 risk threshold.");
    }
    for (GPDecision d : decisions) {
      p.mono(sprintf("%-7s risk %.2f -> %-8s by %s", d.lpId, d.riskScore, d.action, d.gpName));
    }
    p.heading("5. Settlement ledger (double entry)");
    p.mono(sprintf("%-22s %-28s %16s  %s", "Debit", "Credit", "Amount", "Reference"));
    for (LedgerRow l : ledger == null ? new ArrayList<LedgerRow>() : ledger) {
      p.mono(sprintf("%-22s %-28s %16s  %s", l.debit, l.credit, Fmt.usd(l.amountUSD), l.wireReference));
    }
    p.heading("Attestation");
    p.text("This report was generated automatically at the end of the capital call process. Every step (notice,");
    p.text("follow-ups, risk scoring, bridge financing, GP decisions, settlement) is auditable in the Temporal UI.");
    return p.bytes();
  }

  // ─── cancel path ───────────────────────────────────────────────────────

  @Override
  public void markCallCancelled(CallState in) {
    try {
      s.db.exec("UPDATE capital_call_lps SET status='cancelled' WHERE call_id=$1", in.callId);
    } catch (java.sql.SQLException e) {
      throw fail(e.getMessage(), e);
    }
    s.setCallStatus(in.callId, "cancelled");
    for (LP lp : nz(in.lpList)) {
      // Idempotency key callId:lpId. SES must dedupe on it for retries to be safe.
      s.email(m("to", lp.email, "subject", sprintf("Capital Call %s — Cancelled", in.callId),
          "body", "The General Partner has cancelled this capital call. No action is required.",
          "callId", in.callId, "lpId", lp.lpId, "type", "cancellation"), in.callId + ":" + lp.lpId);
    }
    s.event(in.callId, "", "markCallCancelled", "Capital call cancelled by GP; LP response processes terminated");
  }
}
