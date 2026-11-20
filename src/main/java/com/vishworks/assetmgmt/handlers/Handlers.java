package com.vishworks.assetmgmt.handlers;

import static com.vishworks.assetmgmt.util.Format.round2;
import static com.vishworks.assetmgmt.util.Format.sprintf;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.vishworks.assetmgmt.camunda.JobWorkers;
import com.vishworks.assetmgmt.camunda.JobWorkers.Job;
import com.vishworks.assetmgmt.camunda.JobWorkers.JobException;
import com.vishworks.assetmgmt.db.Db;
import com.vishworks.assetmgmt.json.JsonCodec;
import com.vishworks.assetmgmt.models.BridgeResult;
import com.vishworks.assetmgmt.models.CallState;
import com.vishworks.assetmgmt.models.CapitalCallInput;
import com.vishworks.assetmgmt.models.LP;
import com.vishworks.assetmgmt.models.LPResponse;
import com.vishworks.assetmgmt.models.Models;
import com.vishworks.assetmgmt.models.PortfolioRisk;
import com.vishworks.assetmgmt.util.Errors;
import com.vishworks.assetmgmt.util.Format;
import com.vishworks.assetmgmt.util.Log;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The Camunda job workers (BPMN service tasks) of the Intelligent Capital Call &amp;
 * Liquidity Orchestration use case.
 *
 * <p>Each job type maps 1:1 to a service task in bpmn/*.bpmn: issueCapitalCall, notifyLPs,
 * autoFollowUp, recordLPResponse, finalizeResponses, aggregateLiquidity,
 * predictDefaultRisk, scoreLPPortfolio, triggerBridge, escalateToGP, applyGPDecision,
 * settleAndReconcile, emitLiquidityReport, markCallCancelled.
 */
public class Handlers {
  public Db db;
  public String sesUrl;
  public String mlScorerUrl;
  public String creditUrl;
  public String reportDir;
  /** Public URL of the API server / LP portal. */
  public String portalUrl;
  /** Public URL of Operate/Tasklist. */
  public String camundaUrl;
  public HttpClient http;
  /** Timeout of every outbound HTTP call (http.Client{Timeout: 20s}). */
  public Duration httpTimeout = Duration.ofSeconds(20);

  /** Wires all job types onto the worker. */
  public void register(JobWorkers w) {
    w.handle("issueCapitalCall", this::issueCapitalCall);
    w.handle("notifyLPs", this::notifyLPs);
    w.handle("autoFollowUp", this::autoFollowUp);
    w.handle("recordLPResponse", this::recordLPResponse);
    w.handle("finalizeResponses", this::finalizeResponses);
    w.handle("aggregateLiquidity", this::aggregateLiquidity);
    w.handle("predictDefaultRisk", this::predictDefaultRisk);
    w.handle("scoreLPPortfolio", this::scoreLPPortfolio);
    w.handle("triggerBridge", this::triggerBridge);
    w.handle("escalateToGP", this::escalateToGP);
    w.handle("applyGPDecision", this::applyGPDecision);
    w.handle("settleAndReconcile", this::settleAndReconcile);
    w.handle("emitLiquidityReport", this::emitLiquidityReport);
    w.handle("markCallCancelled", this::markCallCancelled);
  }

  // ─── helpers ────────────────────────────────────────────────────────────

  static String isoSeconds(long sec) {
    if (sec < 1) {
      sec = 1;
    }
    return "PT" + sec + "S";
  }

  void event(String callId, String lpId, String step, String detail) {
    Object lp = (lpId == null || lpId.isEmpty()) ? null : lpId;
    try {
      db.exec("INSERT INTO call_events (call_id, lp_id, step, detail) VALUES ($1,$2,$3,$4)", callId, lp, step, detail);
    } catch (SQLException e) {
      Log.printf("event insert: %v", Errors.message(e));
    }
  }

  <T> T postJSON(String url, Object in, TypeReference<T> out) throws IOException, InterruptedException {
    byte[] b = JsonCodec.toJson(in).getBytes(StandardCharsets.UTF_8);
    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
        .timeout(httpTimeout)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(b))
        .build();
    HttpResponse<String> resp;
    try {
      resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      // Wrap transport failures as: Post "<url>": <reason> — the URL is what makes these
      // diagnosable in an incident, since the cause alone rarely names the endpoint.
      throw new IOException("Post \"" + url + "\": " + netReason(url, e), e);
    }
    if (resp.statusCode() >= 300) {
      throw new JobErrorIO(sprintf("POST %s: HTTP %d: %s", url, resp.statusCode(), resp.body()));
    }
    if (out != null) {
      return JsonCodec.MAPPER.readValue(resp.body(), out);
    }
    return null;
  }

  /**
   * A short, stable reason for the common transport failures ("connection refused",
   * "no such host", "i/o timeout"), naming host and port. {@code MockServicesTest} and the
   * incident text both depend on these strings.
   */
  static String netReason(String url, IOException e) {
    URI u = URI.create(url);
    String host = u.getHost() == null ? "" : u.getHost();
    int port = u.getPort() > 0 ? u.getPort() : ("https".equals(u.getScheme()) ? 443 : 80);
    if (Errors.find(e, java.nio.channels.UnresolvedAddressException.class) != null
        || Errors.find(e, java.net.UnknownHostException.class) != null) {
      return "dial tcp: lookup " + host + ": no such host";
    }
    if (Errors.find(e, java.net.http.HttpConnectTimeoutException.class) != null) {
      return "dial tcp " + host + ":" + port + ": i/o timeout";
    }
    if (Errors.find(e, java.net.http.HttpTimeoutException.class) != null) {
      return "context deadline exceeded (Client.Timeout exceeded while awaiting headers)";
    }
    if (Errors.find(e, java.net.ConnectException.class) != null) {
      return "dial tcp " + host + ":" + port + ": connect: connection refused";
    }
    return Errors.message(e);
  }

  /** A returned (non-panic) error raised from an I/O path. */
  static final class JobErrorIO extends IOException {
    JobErrorIO(String m) {
      super(m);
    }
  }

  /** Sends via mock SES. Notification failures are logged, not fatal. */
  void email(Map<String, Object> payload) {
    try {
      postJSON(sesUrl + "/send", payload, null);
    } catch (IOException e) {
      Log.printf("[ses] send failed: %v", Errors.message(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      Log.printf("[ses] send failed: %v", "interrupted");
    }
  }

  void refreshAggregates(String callId) throws SQLException {
    db.exec("""

        		UPDATE capital_calls SET
        		  received_amount_usd = (SELECT COALESCE(SUM(committed_usd),0) FROM capital_call_lps WHERE call_id=$1 AND status='committed'),
        		  updated_at = NOW()
        		WHERE call_id=$1""", callId);
  }

  void setCallStatus(String callId, String status) {
    try {
      db.exec("UPDATE capital_calls SET status=$2, updated_at=NOW() WHERE call_id=$1", callId, status);
    } catch (SQLException e) {
      Log.printf("set status: %v", Errors.message(e));
    }
  }

  /** Builds a variable map from alternating key/value arguments, keys sorted for stable JSON. */
  static Map<String, Object> m(Object... kv) {
    Map<String, Object> out = new TreeMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      out.put((String) kv[i], kv[i + 1]);
    }
    return out;
  }

  // ─── 1. issueCapitalCall ───────────────────────────────────────────────

  /**
   * Creates the call record in the fund admin system, computes each LP's pro-rata draw
   * amount and derives the (demo-compressible) timers.
   */
  public Map<String, Object> issueCapitalCall(Job job) throws Exception {
    CapitalCallInput in = job.bind(CapitalCallInput.class);
    if (in.callId.isEmpty() || in.lpList == null || in.lpList.isEmpty() || in.targetAmountUSD <= 0) {
      throw new JobException("invalid capital call input: callId, lpList and targetAmountUSD are required");
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
    Map<String, Object> draws = new TreeMap<>();
    for (LP lp : in.lpList) {
      double d = round2(in.targetAmountUSD * lp.commitmentUSD / total);
      lp.drawAmountUSD = d;
      draws.put(lp.lpId, d);
    }

    // These go into "timestamp" columns, which carry no zone, so write the local wall clock
    // and read it back as UTC (see Format#rfc3339Nano).
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime deadline = now.plusSeconds((long) in.deadlineDays * spd);
    try (Connection c = db.getConnection()) {
      c.setAutoCommit(false);
      try {
        try {
          Db.exec(c, """

              		INSERT INTO capital_calls (call_id, fund_id, process_instance_key, target_amount_usd, status, deadline_days, seconds_per_day, deadline_date)
              		VALUES ($1,$2,$3,$4,'issued',$5,$6,$7)
              		ON CONFLICT (call_id) DO UPDATE SET process_instance_key=EXCLUDED.process_instance_key""",
              in.callId, in.fundId, job.processInstanceKey(), in.targetAmountUSD, in.deadlineDays, in.secondsPerDay,
              deadline);
        } catch (SQLException e) {
          throw new JobException("insert capital_calls: " + Errors.message(e), e);
        }
        for (LP lp : in.lpList) {
          try {
            Db.exec(c, """

                			INSERT INTO capital_call_lps (call_id, lp_id, email, commitment_usd, draw_amount_usd, status)
                			VALUES ($1,$2,$3,$4,$5,'issued') ON CONFLICT (call_id, lp_id) DO NOTHING""",
                in.callId, lp.lpId, lp.email, lp.commitmentUSD, lp.drawAmountUSD);
          } catch (SQLException e) {
            throw new JobException("insert capital_call_lps: " + Errors.message(e), e);
          }
        }
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(true);
      }
    }
    event(in.callId, "", "issueCapitalCall", sprintf("Call issued for $%.0f across %d LPs (deadline %d days)",
        in.targetAmountUSD, in.lpList.size(), in.deadlineDays));

    // Follow-up schedule: D+3 email, D+7 call, D+10 legal notice, then deadline.
    long fin = (in.deadlineDays - 10L) * spd;
    Map<String, Object> out = new TreeMap<>();
    out.put("lpList", in.lpList);
    out.put("drawAmounts", draws);
    out.put("deadlineDays", in.deadlineDays);
    out.put("waitStage1", isoSeconds(3 * spd));
    out.put("waitStage2", isoSeconds(4 * spd));
    out.put("waitStage3", isoSeconds(3 * spd));
    out.put("waitFinal", isoSeconds(fin));
    out.put("callDeadline", isoSeconds((in.deadlineDays + 3L) * spd));
    out.put("deadlineAt", Format.rfc3339Utc(deadline.atZone(ZoneId.systemDefault()).toInstant()));
    out.put("bridgeTriggered", false);
    out.put("bridgeResult", null);
    out.put("highRiskLPs", List.of());
    out.put("gpDecisions", List.of());
    out.put("settlementMode", "normal");
    return out;
  }

  // ─── 2. notifyLPs ──────────────────────────────────────────────────────

  public Map<String, Object> notifyLPs(Job job) throws Exception {
    CapitalCallInput in = job.bind(CapitalCallInput.class);
    List<LP> lps = in.lpList == null ? List.of() : in.lpList;
    for (LP lp : lps) {
      email(m(
          "to", lp.email,
          "subject", sprintf("Capital Call %s — Action Required", in.callId),
          "body", sprintf("Dear %s, fund %s has issued capital call %s. Your pro-rata draw is $%.2f. Confirm via the secure portal: %s/?call=%s&lp=%s",
              lp.lpId, in.fundId, in.callId, lp.drawAmountUSD, portalUrl, in.callId, lp.lpId),
          "callId", in.callId, "lpId", lp.lpId, "type", "notification"));
    }
    db.exec("UPDATE capital_call_lps SET status='notified', updated_at=NOW() WHERE call_id=$1 AND status='issued'", in.callId);
    setCallStatus(in.callId, "awaiting_lps");
    event(in.callId, "", "notifyLPs", sprintf("Batch notice sent to %d LPs via SES", lps.size()));
    return null;
  }

  // ─── child process: lp-response ────────────────────────────────────────

  /** Variables visible in an lp-response child instance. */
  public static class LpScope {
    @JsonProperty("callId") public String callId = "";
    @JsonProperty("lp") public LP lp = new LP();
    @JsonProperty("amountUSD") public Double amountUSD;
    @JsonProperty("responseStatus") public String responseStatus = "";
    @JsonProperty("followUps") public int followUps;
  }

  static final Map<Integer, String> STAGE_NAMES = Map.of(
      1, "email reminder (D+3)", 2, "phone call (D+7)", 3, "legal notice (D+10)");

  /** Sends a tiered escalation communication to an overdue LP. */
  public Map<String, Object> autoFollowUp(Job job) throws Exception {
    LpScope in = job.bind(LpScope.class);
    int stage = atoi(job.header("stage"));
    String name = STAGE_NAMES.getOrDefault(stage, "");
    email(m(
        "to", in.lp.email,
        "subject", sprintf("Capital Call %s — Follow-up %d: %s", in.callId, stage, name),
        "body", sprintf("LP %s has not confirmed its $%.2f draw. Escalation stage %d (%s).", in.lp.lpId, in.lp.drawAmountUSD, stage, name),
        "callId", in.callId, "lpId", in.lp.lpId, "stage", stage, "type", "followup"));
    db.exec("UPDATE capital_call_lps SET status='overdue', followup_stage=$3, updated_at=NOW() WHERE call_id=$1 AND lp_id=$2",
        in.callId, in.lp.lpId, stage);
    event(in.callId, in.lp.lpId, "autoFollowUp", "Sent " + name);
    return m("followUps", stage);
  }

  /** Parses an int, yielding 0 for anything unparseable — these inputs are already validated. */
  static int atoi(String s) {
    try {
      return Integer.parseInt(s.startsWith("+") ? s.substring(1) : s);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * Finalises one LP: committed / deferred (from the lpCommitment message) or defaulted
   * (deadline passed). Its output lpResponse is collected by the multi-instance call activity.
   */
  public Map<String, Object> recordLPResponse(Job job) throws Exception {
    LpScope in = job.bind(LpScope.class);
    String status = job.header("status");
    double amount = 0.0;
    if (status.equals("committed")) {
      if (in.responseStatus.equals("deferred")) {
        status = "deferred";
      } else {
        amount = in.lp.drawAmountUSD;
        if (in.amountUSD != null && in.amountUSD > 0) {
          amount = in.amountUSD;
        }
      }
    }
    if (status.isEmpty()) {
      status = "defaulted";
    }
    db.exec("UPDATE capital_call_lps SET status=$3, committed_usd=$4, updated_at=NOW() WHERE call_id=$1 AND lp_id=$2",
        in.callId, in.lp.lpId, status, amount);
    refreshAggregates(in.callId);
    String detail = sprintf("LP %s -> %s ($%.0f)", in.lp.lpId, status, amount);
    if (in.followUps > 0) {
      detail += sprintf(" after %d follow-up(s)", in.followUps);
    }
    event(in.callId, in.lp.lpId, "lpResponse", detail);
    LPResponse r = new LPResponse();
    r.lpId = in.lp.lpId;
    r.email = in.lp.email;
    r.commitmentUSD = in.lp.commitmentUSD;
    r.drawAmountUSD = in.lp.drawAmountUSD;
    r.status = status;
    r.amountUSD = amount;
    r.followUps = in.followUps;
    return m("lpResponse", r);
  }

  /**
   * Runs when the response window is closed early (GP force-settlement message) or the
   * hard deadline timer fires on the multi-instance: rebuilds lpResponses from the fund
   * ledger, defaulting every LP that has not committed yet.
   */
  public Map<String, Object> finalizeResponses(Job job) throws Exception {
    CallState in = job.bind(CallState.class);
    String reason = job.header("reason");
    List<LPResponse> out = db.query(
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
    db.exec("UPDATE capital_call_lps SET status='defaulted', committed_usd=0 WHERE call_id=$1 AND status NOT IN ('committed','deferred')", in.callId);
    event(in.callId, "", "finalizeResponses", "Response window closed early (" + reason + "); outstanding LPs marked defaulted");
    // No rows must serialise as null, not [] — the BPMN gateway tests the variable for null.
    return m("lpResponses", out.isEmpty() ? null : out, "settlementMode", reason);
  }

  // ─── 5. aggregateLiquidity ─────────────────────────────────────────────

  public Map<String, Object> aggregateLiquidity(Job job) throws Exception {
    CallState in = job.bind(CallState.class);
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
    db.exec("UPDATE capital_calls SET status='aggregated', received_amount_usd=$2, gap_usd=$3, gap_percent=$4, updated_at=NOW() WHERE call_id=$1",
        in.callId, committed, gap, pct);
    event(in.callId, "", "aggregateLiquidity", sprintf("Committed $%.0f of $%.0f — gap $%.0f (%.2f%%)", committed, in.targetAmountUSD, gap, pct));
    return m("totalCommitted", round2(committed), "gapUSD", round2(gap), "gapPercent", pct);
  }

  static <T> List<T> nz(List<T> l) {
    return l == null ? List.of() : l;
  }

  // ─── 6. predictDefaultRisk ─────────────────────────────────────────────

  /** One element of the ML scorer response. */
  public static class Score {
    @JsonProperty("lpId") public String lpId = "";
    @JsonProperty("riskScore") public double riskScore;
  }

  public Map<String, Object> predictDefaultRisk(Job job) throws Exception {
    CallState in = job.bind(CallState.class);
    List<Score> scores;
    try {
      scores = postJSON(mlScorerUrl + "/score", m("callId", in.callId, "lpResponses", in.lpResponses),
          new TypeReference<List<Score>>() {});
    } catch (IOException e) {
      throw new JobException("ml-scorer: " + Errors.message(e), e);
    }
    Map<String, Double> sc = new LinkedHashMap<>();
    for (Score s : nz(scores)) {
      sc.put(s.lpId, s.riskScore);
    }
    List<LPResponse> high = new ArrayList<>();
    List<LPResponse> responses = in.lpResponses;
    for (LPResponse r : nz(responses)) {
      r.riskScore = sc.getOrDefault(r.lpId, 0.0);
      db.exec("UPDATE capital_call_lps SET risk_score=$3 WHERE call_id=$1 AND lp_id=$2", in.callId, r.lpId, r.riskScore);
      if (r.status.equals("committed") && r.riskScore > Models.HIGH_RISK_THRESHOLD) {
        high.add(r.copy());
      }
    }
    setCallStatus(in.callId, "risk_scored");
    event(in.callId, "", "predictDefaultRisk", sprintf("Scored %d LPs; %d committed LP(s) above %.1f risk threshold",
        nz(responses).size(), high.size(), Models.HIGH_RISK_THRESHOLD));
    return m("lpResponses", responses, "highRiskLPs", high);
  }

  // ─── 6b. scoreLPPortfolio ──────────────────────────────────────────────

  public Map<String, Object> scoreLPPortfolio(Job job) throws Exception {
    CallState in = job.bind(CallState.class);
    List<LPResponse> rs = nz(in.lpResponses);
    double total = 0.0;
    for (LPResponse r : rs) {
      total += r.amountUSD;
    }
    double hhi = 0.0, sumRisk = 0.0, el = 0.0;
    for (LPResponse r : rs) {
      if (total > 0) {
        double s = r.amountUSD / total;
        hhi += s * s;
      }
      sumRisk += r.riskScore;
      el += r.amountUSD * r.riskScore;
    }
    // Stable sort by descending risk, so equal scores keep their input order and the
    // "top risky LPs" list is reproducible.
    List<LPResponse> sorted = new ArrayList<>(rs);
    sorted.sort((a, b) -> Double.compare(b.riskScore, a.riskScore));
    List<String> top = new ArrayList<>();
    for (int i = 0; i < sorted.size() && i < 3; i++) {
      if (sorted.get(i).riskScore > 0.5) {
        top.add(sorted.get(i).lpId);
      }
    }
    PortfolioRisk pr = new PortfolioRisk();
    pr.concentrationScore = Format.round(hhi * 10000) / 10000;
    pr.topRiskyLPs = top;
    pr.averageRiskScore = Format.round(sumRisk / Math.max(1, rs.size()) * 10000) / 10000;
    pr.expectedLossUSD = round2(el);
    db.exec("UPDATE capital_calls SET concentration_hhi=$2 WHERE call_id=$1", in.callId, pr.concentrationScore);
    event(in.callId, "", "scoreLPPortfolio", sprintf("HHI %.4f, top risky %v", pr.concentrationScore, top));
    return m("portfolioRisk", pr);
  }

  // ─── 7. triggerBridge ──────────────────────────────────────────────────

  public Map<String, Object> triggerBridge(Job job) throws Exception {
    CallState in = job.bind(CallState.class);
    BridgeResult br;
    try {
      br = postJSON(creditUrl + "/drawdown", m("callId", in.callId, "amount", in.gapUSD), new TypeReference<BridgeResult>() {});
    } catch (IOException e) {
      throw new JobException("credit facility: " + Errors.message(e), e);
    }
    if (br == null) {
      br = new BridgeResult();
    }
    db.exec("UPDATE capital_calls SET bridge_triggered=TRUE, bridge_confirmation=$2, bridge_amount_usd=$3, bridge_fee_usd=$4 WHERE call_id=$1",
        in.callId, br.confirmationId, br.drawdownUSD, br.feeUSD);
    event(in.callId, "", "triggerBridge", sprintf("Credit line drawdown $%.0f confirmed %s (fee $%.0f)", br.drawdownUSD, br.confirmationId, br.feeUSD));
    return m("bridgeTriggered", true, "bridgeResult", br);
  }

  // ─── 8. escalateToGP / gpApprovalGate ──────────────────────────────────

  /** Variables visible in one GP review sub-process instance. */
  public static class GpScope {
    @JsonProperty("callId") public String callId = "";
    @JsonProperty("highRiskLp") public LPResponse highRiskLp = new LPResponse();
    @JsonProperty("gpAction") public String gpAction = "";
    @JsonProperty("gpName") public String gpName = "";
    @JsonProperty("gpComment") public String gpComment = "";
  }

  public Map<String, Object> escalateToGP(Job job) throws Exception {
    GpScope in = job.bind(GpScope.class);
    LPResponse lp = in.highRiskLp;
    email(m(
        "to", "#gp-investment-committee (Slack)",
        "subject", sprintf("[ESCALATION] High-risk LP %s on call %s", lp.lpId, in.callId),
        "body", sprintf("LP %s committed $%.2f but has default risk %.2f (> %.1f). Decide WAIVE or ENFORCE in Tasklist (%s/tasklist) or via POST /api/capital-calls/%s/gp-decision.",
            lp.lpId, lp.amountUSD, lp.riskScore, Models.HIGH_RISK_THRESHOLD, camundaUrl, in.callId),
        "callId", in.callId, "lpId", lp.lpId, "type", "escalation"));
    db.exec("INSERT INTO gp_escalations (call_id, lp_id, risk_score) VALUES ($1,$2,$3) ON CONFLICT (call_id, lp_id) DO NOTHING",
        in.callId, lp.lpId, lp.riskScore);
    setCallStatus(in.callId, "awaiting_gp");
    event(in.callId, lp.lpId, "escalateToGP", sprintf("Escalated to GP (risk %.2f); awaiting approval gate", lp.riskScore));
    return null;
  }

  /**
   * Runs after the GP approval gate (Tasklist user task or the gpDecision message).
   * "enforce" issues a compliance warning; the LP's commitment stays recorded either way.
   */
  public Map<String, Object> applyGPDecision(Job job) throws Exception {
    GpScope in = job.bind(GpScope.class);
    if (!in.gpAction.equals("enforce")) {
      in.gpAction = "waive";
    }
    if (in.gpName.isEmpty()) {
      in.gpName = "GP Admin";
    }
    LPResponse lp = in.highRiskLp;
    if (in.gpAction.equals("enforce")) {
      email(m(
          "to", lp.email,
          "subject", sprintf("[COMPLIANCE WARNING] Capital Call %s — Enforcement Notice", in.callId),
          "body", sprintf("The General Partner (%s) has issued an enforcement notice on your $%.2f commitment. Your contribution remains recorded and is subject to compliance review.", in.gpName, lp.amountUSD),
          "callId", in.callId, "lpId", lp.lpId, "type", "enforcement_warning"));
    }
    email(m(
        "to", "#gp-investment-committee (Slack)",
        "subject", sprintf("[GP DECISION] %s %s on call %s", in.gpName, in.gpAction, in.callId),
        "body", sprintf("Decision for LP %s: %s. Comment: %s", lp.lpId, in.gpAction, in.gpComment),
        "callId", in.callId, "lpId", lp.lpId, "type", "gp_decision"));
    db.exec("UPDATE gp_escalations SET status='decided', action=$3, gp_name=$4, decided_at=NOW() WHERE call_id=$1 AND lp_id=$2",
        in.callId, lp.lpId, in.gpAction, in.gpName);
    db.exec("UPDATE capital_call_lps SET gp_action=$3 WHERE call_id=$1 AND lp_id=$2", in.callId, lp.lpId, in.gpAction);
    event(in.callId, lp.lpId, "gpApprovalGate", sprintf("GP %s decided: %s", in.gpName, in.gpAction));
    return m("gpAction", in.gpAction, "gpName", in.gpName);
  }

  // ─── 9. settleAndReconcile ─────────────────────────────────────────────

  public Map<String, Object> settleAndReconcile(Job job) throws Exception {
    CallState in = job.bind(CallState.class);
    int wires = 0;
    try (Connection c = db.getConnection()) {
      c.setAutoCommit(false);
      try {
        // Idempotent on job retry: rebuild this call's ledger.
        Db.exec(c, "DELETE FROM ledger_entries WHERE call_id=$1", in.callId);
        for (LPResponse r : nz(in.lpResponses)) {
          Db.exec(c, "UPDATE capital_call_lps SET status=$3, committed_usd=$4 WHERE call_id=$1 AND lp_id=$2",
              in.callId, r.lpId, r.status, r.amountUSD);
          if (!r.status.equals("committed") || r.amountUSD <= 0) {
            continue;
          }
          String wire = sprintf("WIRE-%s-%s", in.callId, r.lpId);
          // Double entry: debit fund cash (asset up), credit LP capital account (contributed capital).
          Db.exec(c, """
              INSERT INTO ledger_entries (call_id, lp_id, debit_account, credit_account, amount_usd, entry_type, wire_reference)
              			VALUES ($1,$2,'fund/cash',$3,$4,'capital_contribution',$5)""",
              in.callId, r.lpId, "lp/" + r.lpId + "/capital", r.amountUSD, wire);
          wires++;
        }
        if (in.bridgeResult != null && in.bridgeResult.drawdownUSD > 0) {
          Db.exec(c, """
              INSERT INTO ledger_entries (call_id, debit_account, credit_account, amount_usd, entry_type, wire_reference)
              			VALUES ($1,'fund/cash','liability/credit-facility',$2,'bridge_drawdown',$3)""",
              in.callId, in.bridgeResult.drawdownUSD, in.bridgeResult.confirmationId);
          Db.exec(c, """
              INSERT INTO ledger_entries (call_id, debit_account, credit_account, amount_usd, entry_type, wire_reference)
              			VALUES ($1,'expense/financing-fees','fund/cash',$2,'bridge_fee',$3)""",
              in.callId, in.bridgeResult.feeUSD, in.bridgeResult.confirmationId);
        }
        Db.exec(c, "UPDATE capital_calls SET status='settled', updated_at=NOW() WHERE call_id=$1", in.callId);
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(true);
      }
    }
    event(in.callId, "", "settleAndReconcile", sprintf("%d wire transfers booked; double-entry ledger reconciled", wires));
    return m("wiresSettled", wires);
  }

  // ─── 10. emitLiquidityReport ───────────────────────────────────────────

  public Map<String, Object> emitLiquidityReport(Job job) throws Exception {
    CallState in = job.bind(CallState.class);
    String[] paths = ReportWriter.writeReport(this, in, job.processInstanceKey());
    String jsonPath = paths[0], pdfPath = paths[1];
    db.exec("UPDATE capital_calls SET status='completed', report_path=$2, updated_at=NOW() WHERE call_id=$1",
        in.callId, "/reports/" + in.callId + ".pdf");
    event(in.callId, "", "emitLiquidityReport", "Regulatory liquidity report emitted (PDF + JSON)");
    return m("reportPdf", pdfPath, "reportJson", jsonPath);
  }

  // ─── cancel path ───────────────────────────────────────────────────────

  public Map<String, Object> markCallCancelled(Job job) throws Exception {
    CallState in = job.bind(CallState.class);
    db.exec("UPDATE capital_call_lps SET status='cancelled' WHERE call_id=$1", in.callId);
    setCallStatus(in.callId, "cancelled");
    for (LP lp : nz(in.lpList)) {
      email(m("to", lp.email, "subject", sprintf("Capital Call %s — Cancelled", in.callId),
          "body", "The General Partner has cancelled this capital call. No action is required.",
          "callId", in.callId, "lpId", lp.lpId, "type", "cancellation"));
    }
    event(in.callId, "", "markCallCancelled", "Capital call cancelled by GP; LP response processes terminated");
    return null;
  }

  /** UTC "now", used by the report. */
  static java.time.ZonedDateTime nowUtc() {
    return java.time.ZonedDateTime.now(ZoneOffset.UTC);
  }
}
