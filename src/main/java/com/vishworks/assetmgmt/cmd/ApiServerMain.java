package com.vishworks.assetmgmt.cmd;

import static com.vishworks.assetmgmt.util.Env.get;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sun.net.httpserver.HttpExchange;
import com.vishworks.assetmgmt.camunda.Camunda;
import com.vishworks.assetmgmt.db.Db;
import com.vishworks.assetmgmt.http.FileServer;
import com.vishworks.assetmgmt.http.Http;
import com.vishworks.assetmgmt.http.Router;
import com.vishworks.assetmgmt.json.JsonCodec;
import com.vishworks.assetmgmt.models.CapitalCallInput;
import com.vishworks.assetmgmt.models.LP;
import com.vishworks.assetmgmt.models.Models;
import com.vishworks.assetmgmt.util.Errors;
import com.vishworks.assetmgmt.util.Format;
import com.vishworks.assetmgmt.util.Log;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.CorrelateMessageResponse;
import io.camunda.client.api.response.ProcessInstanceEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exposes the capital-call REST/webhook endpoints (LP portal webhook, GP decision, force
 * settlement, cancel) on top of Camunda 8, plus a read-only live dashboard backed by the
 * fund ledger database (port of cmd/apiserver).
 */
public final class ApiServerMain {
  private final Db db;
  private final CamundaClient cam;
  private final String camPublic;
  private final String sesPublic;
  private final byte[] dashboardHtml;

  ApiServerMain(Db db, CamundaClient cam, String camPublic, String sesPublic, byte[] dashboardHtml) {
    this.db = db;
    this.cam = cam;
    this.camPublic = camPublic;
    this.sesPublic = sesPublic;
    this.dashboardHtml = dashboardHtml;
  }

  public static void main(String[] args) throws Exception {
    Db pool;
    try {
      pool = Db.connect(get("DATABASE_URL", "postgres://assetmgmt:assetmgmt@localhost:5433/assetmgmt?sslmode=disable"));
    } catch (Exception e) {
      Log.fatal(e);
      return;
    }
    byte[] html;
    try (InputStream in = ApiServerMain.class.getResourceAsStream("/static/dashboard.html")) {
      html = in.readAllBytes(); // bundled into the jar as a resource, so there is no file to mount
    }
    ApiServerMain s = new ApiServerMain(pool,
        Camunda.newClient(get("CAMUNDA_REST_ADDRESS", "http://localhost:8080")),
        get("CAMUNDA_PUBLIC_URL", "http://localhost:8080"),
        get("SES_PUBLIC_URL", "http://localhost:8081"),
        html);

    String addr = get("LISTEN_ADDR", ":8090");
    Log.printf("API server listening on %s", addr);
    try {
      Http.listen(addr, logReq(s.routes()));
    } catch (IOException e) {
      Log.fatal(e);
    }
  }

  Router routes() {
    Router mux = new Router();
    mux.handle("GET /{$}", r -> Http.send(r.ex, 200, "text/html; charset=utf-8", dashboardHtml));
    mux.handle("GET /health", r -> Http.writeJSON(r.ex, 200, Map.of("status", "ok")));
    mux.handle("GET /api/config", r -> Http.writeJSON(r.ex, 200, new TreeMap<>(Map.of("camundaUrl", camPublic, "sesUrl", sesPublic))));
    mux.handle("GET /reports/", new FileServer(Path.of(get("REPORT_DIR", "./reports"))));

    mux.handle("GET /api/lps", this::listLPs);
    mux.handle("GET /api/capital-calls", this::listCalls);
    mux.handle("POST /api/capital-calls", this::startCall);
    mux.handle("GET /api/capital-calls/{callId}", this::getCall);
    mux.handle("POST /api/capital-calls/{callId}/lp-response", this::lpResponse);
    mux.handle("POST /api/capital-calls/{callId}/gp-decision", this::gpDecision);
    mux.handle("POST /api/capital-calls/{callId}/force-settlement", simpleMessage(Models.MSG_FORCE_SETTLEMENT));
    mux.handle("POST /api/capital-calls/{callId}/cancel-call", simpleMessage(Models.MSG_CANCEL_CALL));
    return mux;
  }

  /** Logs every non-GET request (logReq middleware). */
  static com.sun.net.httpserver.HttpHandler logReq(Router h) {
    return ex -> {
      if (!"GET".equals(ex.getRequestMethod())) {
        Log.printf("%s %s", ex.getRequestMethod(), ex.getRequestURI().getPath());
      }
      h.handle(ex);
    };
  }

  static Map<String, Object> obj(Object... kv) {
    Map<String, Object> m = new TreeMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  /**
   * apiErr: Camunda client errors (HTTP status below 500) keep their status; everything
   * else (5xx, connection failures) becomes 502 Bad Gateway.
   */
  static void apiErr(HttpExchange ex, Throwable err) throws IOException {
    int code = 502;
    int st = Camunda.status(err);
    if (st > 0 && st < 500) {
      code = st;
    }
    Http.writeJSON(ex, code, obj("error", Camunda.message(err)));
  }

  // ─── queries ────────────────────────────────────────────────────────────

  void listLPs(Router.Request r) throws IOException {
    try {
      Http.writeJSON(r.ex, 200, db.rowsToMaps("SELECT lp_id, name, commitment_usd::float8 AS commitment_usd, email, demo_behavior FROM lps ORDER BY lp_id"));
    } catch (SQLException e) {
      Http.writeJSON(r.ex, 500, obj("error", Errors.message(e)));
    }
  }

  void listCalls(Router.Request r) throws IOException {
    try {
      Http.writeJSON(r.ex, 200, db.rowsToMaps("""

          		SELECT c.call_id, c.fund_id, c.process_instance_key, c.status, c.target_amount_usd::float8 AS target_amount_usd,
          		       c.received_amount_usd::float8 AS received_amount_usd, c.gap_percent::float8 AS gap_percent, c.bridge_triggered,
          		       c.report_path, c.created_at,
          		       (SELECT COUNT(*) FROM capital_call_lps l WHERE l.call_id=c.call_id AND l.status='committed') AS committed_count,
          		       (SELECT COUNT(*) FROM capital_call_lps l WHERE l.call_id=c.call_id) AS lp_count
          		FROM capital_calls c ORDER BY c.created_at DESC LIMIT 50"""));
    } catch (SQLException e) {
      Http.writeJSON(r.ex, 500, obj("error", Errors.message(e)));
    }
  }

  /**
   * A secondary query for the dashboard: a failure yields null rather than propagating, so one
   * broken panel cannot fail the whole response.
   */
  private List<Map<String, Object>> rowsOrNull(String sql, Object... args) {
    try {
      return db.rowsToMaps(sql, args);
    } catch (SQLException e) {
      return null;
    }
  }

  void getCall(Router.Request r) throws IOException {
    String id = r.pathValue("callId");
    List<Map<String, Object>> call;
    try {
      call = db.rowsToMaps("""
          SELECT call_id, fund_id, process_instance_key, status, target_amount_usd::float8 AS target_amount_usd,
          		received_amount_usd::float8 AS received_amount_usd, gap_usd::float8 AS gap_usd, gap_percent::float8 AS gap_percent, bridge_triggered,
          		bridge_confirmation, bridge_amount_usd::float8 AS bridge_amount_usd, bridge_fee_usd::float8 AS bridge_fee_usd,
          		concentration_hhi::float8 AS concentration_hhi, report_path, deadline_days, seconds_per_day, deadline_date, created_at
          		FROM capital_calls WHERE call_id=$1""", id);
    } catch (SQLException e) {
      call = null;
    }
    if (call == null || call.isEmpty()) {
      Http.writeJSON(r.ex, 404, obj("error", "capital call not found (it appears once issueCapitalCall has run)"));
      return;
    }
    List<Map<String, Object>> lps = rowsOrNull("""
        SELECT l.lp_id, p.name, l.commitment_usd::float8 AS commitment_usd, l.draw_amount_usd::float8 AS draw_amount_usd,
        		l.committed_usd::float8 AS committed_usd, l.status, l.followup_stage, l.risk_score::float8 AS risk_score, l.gp_action, p.demo_behavior
        		FROM capital_call_lps l LEFT JOIN lps p ON p.lp_id=l.lp_id WHERE l.call_id=$1 ORDER BY l.lp_id""", id);
    List<Map<String, Object>> esc = rowsOrNull("SELECT lp_id, risk_score::float8 AS risk_score, status, action, gp_name, created_at, decided_at FROM gp_escalations WHERE call_id=$1 ORDER BY lp_id", id);
    List<Map<String, Object>> ledger = rowsOrNull("SELECT lp_id, debit_account, credit_account, amount_usd::float8 AS amount_usd, entry_type, wire_reference FROM ledger_entries WHERE call_id=$1 ORDER BY id", id);
    List<Map<String, Object>> events = rowsOrNull("SELECT lp_id, step, detail, created_at FROM call_events WHERE call_id=$1 ORDER BY id", id);
    Http.writeJSON(r.ex, 200, obj("call", call.get(0), "lps", lps, "escalations", esc, "ledger", ledger, "events", events));
  }

  // ─── commands ───────────────────────────────────────────────────────────

  /** Body of POST /api/capital-calls. */
  public static class StartReq {
    @JsonProperty("callId") public String callId = "";
    @JsonProperty("fundId") public String fundId = "";
    @JsonProperty("targetAmountUSD") public double targetAmountUSD;
    @JsonProperty("deadlineDays") public int deadlineDays;
    @JsonProperty("secondsPerDay") public int secondsPerDay;
    @JsonProperty("lpIds") public List<String> lpIds;
    @JsonProperty("lpList") public List<LP> lpList;
  }

  void startCall(Router.Request r) throws IOException {
    StartReq req;
    try {
      req = JsonCodec.decodeBody(r.body(), StartReq.class);
    } catch (JsonCodec.DecodeException e) {
      Http.writeJSON(r.ex, 400, obj("error", "invalid JSON: " + e.getMessage()));
      return;
    }
    if (req.callId.isEmpty()) {
      req.callId = Format.sprintf("CC-%d-%04d", Year.now().getValue(), ThreadLocalRandom.current().nextInt(9000) + 1000);
    }
    if (req.fundId.isEmpty()) {
      req.fundId = "FUND-GROWTH-IV";
    }
    if (req.targetAmountUSD <= 0) {
      req.targetAmountUSD = 50_000_000;
    }
    if (req.deadlineDays <= 0) {
      req.deadlineDays = 10;
    }
    if (req.lpList == null || req.lpList.isEmpty()) {
      try {
        String[] ids = req.lpIds == null ? null : req.lpIds.toArray(new String[0]);
        List<LP> found = db.query(
            "SELECT lp_id, commitment_usd::float8, email FROM lps WHERE COALESCE(cardinality($1::text[]),0)=0 OR lp_id = ANY($1) ORDER BY lp_id",
            rs -> {
              LP lp = new LP();
              lp.lpId = rs.getString(1);
              lp.commitmentUSD = rs.getDouble(2);
              lp.email = rs.getString(3);
              return lp;
            },
            (Object) ids);
        req.lpList = new java.util.ArrayList<>(found);
      } catch (SQLException e) {
        Http.writeJSON(r.ex, 500, obj("error", Errors.message(e)));
        return;
      }
    }
    if (req.lpList.isEmpty()) {
      Http.writeJSON(r.ex, 400, obj("error", "no LPs selected"));
      return;
    }
    CapitalCallInput in = new CapitalCallInput();
    in.callId = req.callId;
    in.fundId = req.fundId;
    in.targetAmountUSD = req.targetAmountUSD;
    in.lpList = req.lpList;
    in.deadlineDays = req.deadlineDays;
    in.secondsPerDay = req.secondsPerDay;
    ProcessInstanceEvent res;
    try {
      res = cam.newCreateInstanceCommand()
          .bpmnProcessId(Models.PROCESS_CAPITAL_CALL)
          .latestVersion()
          .variables(JsonCodec.toJson(in))
          .send()
          .join();
    } catch (RuntimeException e) {
      apiErr(r.ex, e);
      return;
    }
    String pik = Long.toString(res.getProcessInstanceKey());
    Http.writeJSON(r.ex, 201, obj(
        "callId", in.callId,
        "processInstanceKey", pik,
        "operateUrl", Format.sprintf("%s/operate/processes/%s", camPublic, pik),
        "lpCount", in.lpList.size()));
  }

  /** Body of POST …/lp-response. */
  public static class LpResponseBody {
    @JsonProperty("lpId") public String lpId = "";
    @JsonProperty("amount") public Double amount;
    @JsonProperty("amountUSD") public Double amountUSD;
    @JsonProperty("status") public String status = "";
    /** Accepted for API compatibility; unused with Camunda. */
    @JsonProperty("runId") public String runId = "";
  }

  /**
   * The LP-portal webhook. Published as a buffered message so an early response is not
   * lost, and idempotent per (call, LP) via messageId.
   */
  void lpResponse(Router.Request r) throws IOException {
    String callId = r.pathValue("callId");
    LpResponseBody body;
    try {
      body = JsonCodec.decodeBody(r.body(), LpResponseBody.class);
    } catch (JsonCodec.DecodeException e) {
      body = null;
    }
    if (body == null || body.lpId.isEmpty()) {
      Http.writeJSON(r.ex, 400, obj("error", "lpId is required"));
      return;
    }
    Double amount = body.amount;
    if (amount == null) {
      amount = body.amountUSD;
    }
    String status = "committed";
    if (body.status.equals("deferred")) {
      status = "deferred";
    }
    Map<String, Object> vars = obj("responseStatus", status);
    if (amount != null) {
      vars.put("amountUSD", amount);
    }
    String key = callId + ":" + body.lpId;
    try {
      cam.newPublishMessageCommand()
          .messageName(Models.MSG_LP_COMMITMENT)
          .correlationKey(key)
          .messageId("lpCommitment-" + key)
          .timeToLive(Duration.ofHours(1))
          .variables(JsonCodec.toJson(vars))
          .send()
          .join();
    } catch (RuntimeException e) {
      apiErr(r.ex, e);
      return;
    }
    Http.writeJSON(r.ex, 202, obj("message", Models.MSG_LP_COMMITMENT, "correlationKey", key, "variables", vars));
  }

  /** Body of POST …/gp-decision. */
  public static class GpDecisionBody {
    @JsonProperty("lpId") public String lpId = "";
    @JsonProperty("action") public String action = "";
    @JsonProperty("gpName") public String gpName = "";
    @JsonProperty("comment") public String comment = "";
  }

  /** Completes the GP approval gate from outside Tasklist (e.g. Slack button). */
  void gpDecision(Router.Request r) throws IOException {
    String callId = r.pathValue("callId");
    GpDecisionBody body;
    try {
      body = JsonCodec.decodeBody(r.body(), GpDecisionBody.class);
    } catch (JsonCodec.DecodeException e) {
      body = null;
    }
    if (body == null || body.lpId.isEmpty() || (!body.action.equals("waive") && !body.action.equals("enforce"))) {
      Http.writeJSON(r.ex, 400, obj("error", "lpId and action (\"waive\"|\"enforce\") are required"));
      return;
    }
    String key = callId + ":" + body.lpId;
    CorrelateMessageResponse res;
    try {
      res = correlate(Models.MSG_GP_DECISION, key, obj("gpAction", body.action, "gpName", body.gpName, "gpComment", body.comment));
    } catch (RuntimeException e) {
      if (Camunda.isNotFound(e)) {
        Http.writeJSON(r.ex, 409, obj("error", "no GP approval gate is currently open for " + key));
        return;
      }
      apiErr(r.ex, e);
      return;
    }
    Http.writeJSON(r.ex, 200, obj("correlated", true, "correlationKey", key, "result", result(res)));
  }

  Router.Handler simpleMessage(String name) {
    return r -> {
      String callId = r.pathValue("callId");
      CorrelateMessageResponse res;
      try {
        res = correlate(name, callId, obj());
      } catch (RuntimeException e) {
        if (Camunda.isNotFound(e)) {
          Http.writeJSON(r.ex, 409, obj("error", "capital call " + callId + " is not in the LP response window"));
          return;
        }
        apiErr(r.ex, e);
        return;
      }
      Http.writeJSON(r.ex, 200, obj("correlated", true, "message", name, "result", result(res)));
    };
  }

  private CorrelateMessageResponse correlate(String name, String key, Map<String, Object> vars) {
    return cam.newCorrelateMessageCommand()
        .messageName(name)
        .correlationKey(key)
        .variables(JsonCodec.toJson(vars))
        .send()
        .join();
  }

  /**
   * Camunda's correlation response, passed through as {"messageKey","processInstanceKey",
   * "tenantId"} with sorted keys and the 64-bit keys as strings — JSON numbers would lose
   * precision in the browser.
   */
  static Map<String, Object> result(CorrelateMessageResponse res) {
    Map<String, Object> m = new TreeMap<>();
    if (res.getMessageKey() != null) m.put("messageKey", Long.toString(res.getMessageKey()));
    if (res.getProcessInstanceKey() != null) m.put("processInstanceKey", Long.toString(res.getProcessInstanceKey()));
    if (res.getTenantId() != null) m.put("tenantId", res.getTenantId());
    return m;
  }
}
